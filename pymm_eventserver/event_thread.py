"""ZMQ based communication to Micro-Manager via the PythonEventServer plugin.

Based on Pycromanager (https://github.com/micro-manager/pycro-manager) to facilitate receiving
events that are emitted by the different parts of Micro-Manager for GUI inputs and during
acquisition. Used with some ImageAnalysers to receive images and to react to starting and ending
acquisitions.
"""

import json
import logging
import re
import time
from typing import Union
import numpy as np

import zmq
from pycromanager import JavaObject, Studio, Core
from pycromanager.zmq_bridge._bridge import _Bridge
from qtpy.QtCore import QObject, QThread, Signal, Slot

from pymm_eventserver.data_structures import MMSettings
from pymm_eventserver.data_structures import PyImage

log = logging.getLogger("EDA")
SOCKET = "5556"


class EventThread(QObject):
    """Thread that receives events from Micro-Manager and relays them to the main program."""

    def __init__(self, live_images: bool = False, topics: Union[str, list] = "all"):
        """Set up the bridge to Micro-Manager, ZMQ sockets and the main listener Thread."""
        super().__init__()

        self.live_images = live_images

        self.bridge = _Bridge(debug=False)

        # Make sockets that events circle through to always have a ready socket
        self.event_sockets = []
        self.num_sockets = 5
        for socket in range(self.num_sockets):
            bridge = _Bridge()
            self.event_sockets.append(bridge)

        # PUB/SUB implementation of ZMQ
        self.context = zmq.Context()
        self.socket = self.context.socket(zmq.SUB)
        self.socket.connect("tcp://localhost:" + SOCKET)
        self.socket.setsockopt(zmq.RCVTIMEO, 1000)  # Timeout for the recv() function

        self.thread_stop = False

        if not isinstance(topics, list):
            self.topics = [
                "StandardEvent",
                "GUIRefreshEvent",
                "LiveMode",
                "Acquisition",
                "GUI",
                "Hardware",
                "Settings",
                "NewImage",
            ]
        else:
            self.topics = topics

        for topic in self.topics:
            self.socket.setsockopt_string(zmq.SUBSCRIBE, topic)

        # Set up the main listener Thread
        self.thread = QThread()
        self.listener = EventListener(
            self.socket, self.event_sockets, self.bridge, self.thread, self.live_images
        )
        self.listener.moveToThread(self.thread)
        self.thread.started.connect(self.listener.start)
        self.listener.stop_thread_event.connect(self.stop)
        self.thread.start()

    def stop(self):
        """Close the QThread and the zmq sockets."""
        self.thread.exit()
        while self.thread.isRunning():
            time.sleep(0.05)

        log.info("Closing zmq socket")
        self.socket.close()
        for socket in self.event_sockets:
            socket.close()
        self.context.term()


class EventListener(QObject):
    """Loop running in a QThread that listens to events published on the spcified zmq sockets.

    There are additional events that could be listened to. Many can be found here:
    https://valelab4.ucsf.edu/~MM/doc-2.0.0-gamma/mmstudio/org/micromanager/events/package-summary.html
    But also in the index.
    """

    acquisition_started_event = Signal(object)
    acquisition_ended_event = Signal(object)
    new_image_event = Signal(PyImage)
    configuration_settings_event = Signal(str, str, str)
    stop_thread_event = Signal()
    mda_settings_event = Signal(MMSettings)
    live_mode_event = Signal(bool)
    xy_stage_position_changed_event = Signal(tuple)
    stage_position_changed_event = Signal(float)
    exposure_changed_event = Signal(str, str, str)

    def __init__(
        self,
        socket,
        event_sockets,
        bridge: _Bridge,
        thread: QThread,
        send_live_images: bool = False,
    ):
        """Store passed arguments and starting time for frequency limitation of certain events."""
        super().__init__()
        self.send_live_images = send_live_images
        self.loop_stop = False
        self.socket = socket
        self.event_sockets = event_sockets
        self.bridge = bridge
        self.studio = Studio()
        self.core = Core()
        self.thread = thread
        # Record times for events that we receive twice
        self.last_acq_started = time.perf_counter()
        self.last_custom_mda = time.perf_counter()
        self.last_stage_position = time.perf_counter()
        self.blockZ = False
        self.blockImages = False
        self.timeouts = 0

    @Slot()
    def start(self):
        """Listen on the zmq socket.

        This receives the events on the socket and translates them to the event as a python shadow
        of the java object. Using pycromanager, the relevant data can be pulled over to python. This
        is done depending on which event was originally sent from Java. PyQtSignals are then emitted
        with the data. Normally the EventBus will subscribe to these events to pass them on to the
        parts of the EDA loop.
        """
        instance = 0
        print("EventServer running")
        while not self.loop_stop:
            instance = instance + 1 if instance < 100 else 0
            eventString = "NoEvent"
            try:
                #  Get the reply.
                reply = str(self.socket.recv())
                # topic = re.split(' ', reply)[0][2:]

                # Translate the event to a shadow object

                try:
                    message = json.loads(re.split(" ", reply)[1][0:-1])
                    evt, eventString = self.translate_message(message, instance)
                except json.decoder.JSONDecodeError:
                    if self.blockImages:
                        return
                    print("ImageEvent")
                    image_bit = str(self.socket.recv())
                    # TODO: Maybe this should also be done for other bitdepths?!
                    image_depth = np.uint16 if image_bit == "b'2'" else np.uint8
                    metadata = self.socket.recv()
                    metadata_dict = json.loads(str(metadata)[11:-1])
                    next_message = self.socket.recv()
                    py_image = self.image_from_message(
                        next_message, reply, image_depth, metadata_dict
                    )
                    self.new_image_event.emit(py_image)
                print(eventString)
                if "DefaultAcquisitionStartedEvent" in eventString:
                    if time.perf_counter() - self.last_acq_started > 0.2:
                        self.acquisition_started_event.emit(evt)
                    else:
                        print("SKIPPED")
                    self.last_acq_started = time.perf_counter()
                elif "DefaultAcquisitionEndedEvent" in eventString:
                    self.acquisition_ended_event.emit(evt)
                elif "CustomSettingsEvent" in eventString:
                    dev = evt.get_device()
                    prop = evt.get_property()
                    val = evt.get_value()
                    self.configuration_settings_event.emit(dev, prop, val)
                elif "DefaultExposureChangedEvent" in eventString:
                    self.exposure_changed_event.emit(
                        "exposure", "time_ms", str(evt.get_new_exposure_time()))
                elif "DefaultStagePositionChangedEvent" in eventString:
                    if self.blockZ > 0 or time.perf_counter() - self.last_stage_position < 0.05:
                        print("BLOCKED ", self.blockZ)
                    else:
                        self.stage_position_changed_event.emit(evt.get_pos() * 100)
                    self.last_stage_position = time.perf_counter()
                    self.blockZ = False
                elif "CustomMDAEvent" in eventString:
                    if time.perf_counter() - self.last_custom_mda > 0.2:
                        settings = evt.get_settings()
                        settings = MMSettings(java_settings=settings)
                        self.mda_settings_event.emit(settings)
                    self.last_custom_mda = time.perf_counter()
                elif "DefaultLiveModeEvent" in eventString:
                    if not self.send_live_images:
                        self.blockImages = evt.get_is_on()
                    self.live_mode_event.emit(self.blockImages)
                elif "XYStagePositionChangedEvent" in eventString:
                    self.xy_stage_position_changed_event.emit((evt.get_x_pos(), evt.get_y_pos()))
            except zmq.error.Again:
                self.timeouts += 1
                # print("Server timeout", self.timeouts)
                pass

    def image_from_message(self, message, reply, image_depth, metadata_dict):
        dt = np.dtype(image_depth)
        dt = dt.newbyteorder('>')
        image = np.frombuffer(message, dtype=dt)
        image_params = re.split("NewImage ", reply)[1]
        image_params = re.split(", ", image_params[1:-2])
        image_params = [int(round(float(x))) for x in image_params]
        py_image = PyImage(
            image.reshape([int(image_params[0]), int(image_params[1])]),
            metadata_dict,
            *image_params[2:]
        )
        return py_image

    def translate_message(self, message, instance):
        socket_num = instance % len(self.event_sockets)

        eventString = message["class"].split(r".")[-1]
        log.info(eventString)
        pre_evt = self.bridge._class_factory.create(message)
        evt = pre_evt(
            serialized_object=message,
            bridge=self.event_sockets[socket_num],
        )
        return evt, eventString

    @Slot()
    def stop(self):
        """Thread was stopped, let's also close the socket then."""
        self.loop_stop = True
        self.stop_thread_event.emit()
        while self.thread.isRunning():
            time.sleep(0.05)


def main():
    """Start an EventThread, can be used to test PythonEventServer plugin from Micro-Manager."""
    thread = EventThread()
    print("Stop using keyboard interrupt")
    while True:
        try:
            time.sleep(0.01)
        except KeyboardInterrupt:
            thread.listener.stop()
            print("Stopping")
            break


if __name__ == "__main__":
    main()
