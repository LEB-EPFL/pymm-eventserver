
package org.micromanager.plugins.pythoneventserver;

import com.google.common.eventbus.Subscribe;
import mmcorej.CMMCore;
import mmcorej.org.json.JSONObject;
import org.micromanager.Studio;
import org.micromanager.acquisition.AcquisitionEndedEvent;
import org.micromanager.acquisition.AcquisitionSequenceStartedEvent;
import org.micromanager.acquisition.SequenceSettings;
import org.micromanager.data.DataProviderHasNewImageEvent;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.events.*;
import org.micromanager.internal.ConfigGroupPad;
import org.micromanager.internal.MMStudio;
import org.micromanager.internal.interfaces.AcqSettingsListener;
import org.micromanager.internal.utils.WindowPositioning;
import org.micromanager.internal.zmq.ZMQUtil;
import org.zeromq.SocketType;

import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.*;

import static org.zeromq.ZMQ.*;

/**
 * ZMQ based Server that relays Micro-Manager events to python
 * <p>
 * THis is called and started when PythonEventServer Plugin is
 * started from Micro-Manager. It starts a Thread that uses a
 * ZMQ PUB socket to publish events that can be subscribed to from
 * python. This can run in parallel with pycromanager to make use
 * of those events.
 * <p>
 * To get the events, the thread implements AcqSettingsListener and
 * subscribes to the EventBus via the EventManager of Micro-Manager.
 * When an event is received it relays the same event to the custom
 * socket. Exceptions are GUIRefreshEvents, that try to get info
 * from the Configuration settings to see if it was triggered by
 * a change there. It then sends out this information using the
 * CustomSettinsEvent class. This is because there does not seem
 * to be another event triggered by that.
 * The other exception are changes in theMDA window, where the
 * AcqSettingsListener is notified and just sends the full Settings.
 *
 * @author Willi Stepp
 * @version 0.1
 */

public class PythonEventServerFrame extends JFrame {

   private final JCheckBox liveCheckBox_;
   private final Studio studio_;
   private ConfigGroupPad pad_;
   private final JTextArea logTextArea;
   private Socket socket_;
   private final ServerThread server;
   public ZMQUtil util_;
   private long lastStageTime;


   public PythonEventServerFrame(Studio studio) {
      super("Python Event Server GUI");
      studio_ = studio;
//      AcqControlDlg acw_ = ((MMStudio) studio_).uiManager().getAcquisitionWindow();
      // Set up the server
      server = new ServerThread();
      server.init();
      server.start();
      System.out.println("Server started");

      // try the same with the mmserver
      CMMCore core = studio_.getCMMCore();
      HashSet<ClassLoader> classLoaders = new HashSet<>();
      classLoaders.add(core.getClass().getClassLoader());
      util_ = new ZMQUtil(classLoaders, new String[]{});

      // Add a function that closes the server when the window is closed
      super.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
      super.addWindowListener(new WindowAdapter() {
         @Override
         public void windowClosing(WindowEvent e) {
            exitPlugin();
         }
      });

      // Adjust the window
      JLabel title = new JLabel("Python Event Server");
      title.setFont(new Font("Arial", Font.BOLD, 14));
      title.setMaximumSize( new Dimension(200, 30));
      title.setAlignmentX(CENTER_ALIGNMENT);


      liveCheckBox_ = new javax.swing.JCheckBox();
      liveCheckBox_.setText("Live Mode Events");

      logTextArea = new JTextArea(30,100);
      logTextArea.setAlignmentX(CENTER_ALIGNMENT);

      JScrollPane scrollPane = new JScrollPane(logTextArea);
      DefaultCaret caret = (DefaultCaret)logTextArea.getCaret();
      caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

      JPanel panel = new JPanel();
      BoxLayout boxLayout = new BoxLayout(panel, BoxLayout.Y_AXIS);
      panel.setLayout(boxLayout);
      panel.add(title);
      panel.add(scrollPane);
      panel.add(liveCheckBox_);
      panel.setAlignmentX(CENTER_ALIGNMENT);

      super.add(panel);
      super.setIconImage(Toolkit.getDefaultToolkit().getImage(
              getClass().getResource("/org/micromanager/icons/microscope.gif")));
      super.setLocation(100, 100);
      super.setSize(new Dimension(100, 100));
      WindowPositioning.setUpLocationMemory(this, this.getClass(), null);
      super.pack();

   }

   public void exitPlugin(){
      server.close();
      super.dispose();
      System.out.println("Closed Server and Frame");
   }


   public class ServerThread extends Thread implements AcqSettingsListener {
      private ImageListener imageListener_;


      public void init(){
         final Context context = context(1);
         socket_ = context.socket(SocketType.PUB);
//         socket_.bind("tcp://localhost:5556");
         socket_.bind("tcp://*:5556");
         studio_.events().registerForEvents(this);
         MMStudio studio;
         studio = (MMStudio) studio_;

         pad_ =  studio.uiManager().frame().getConfigPad();

         ((MMStudio) studio_).getAcquisitionEngine().addSettingsListener(this);

         lastStageTime = System.currentTimeMillis();
      }

      public void close(){
         socket_.unbind("tcp://*:5556");
         socket_.close();
         studio_.events().unregisterForEvents(this);
         super.interrupt();
      }

      @Subscribe
      public void onConfigGroupChanged(ConfigGroupChangedEvent event) {
         addLog("ConfigGroupChangedEvent " + event.getGroupName());
      }

      @Subscribe
      public void onGUIRefresh(GUIRefreshEvent event){
         // This also fires every time some value in the Table is changed, as we don't get events directly from that,
         // we will construct our own event and send that out again over the EventBus, so that things are consistent.
         String group = pad_.getSelectedGroup();
         // Get what changed from the table
         // Put this information into a List of CustomSettingsEvents for each setting that has been changed
         String state;
         java.util.List<CustomSettingsEvent> changedSettings = new ArrayList<>();
         try {
            state = studio_.core().getConfigGroupState(group).getVerbose();
            // state looks something like this: <html>Dummy_488_Power:Power (% of max)=99.8000<br></html>
            // with different settings that are changed separated by <br>s
            System.out.println(state);
            String[] changeList = state.substring(6, state.length() - 7).split("<br>");
            for (String change : changeList) {
               System.out.println(change);
               String device = change.split(":")[0];
               String setting = change.split(":")[1].split("=")[0];
               String value = change.split(":")[1].split("=")[1];
               changedSettings.add(new CustomSettingsEvent(device, setting, value));
            }
         } catch (Exception e) {
            System.out.println("Error");
            e.printStackTrace();
         }

         if (changedSettings.isEmpty()) {
//            No setting has changed we will send the current exposure time setting
            double exposure = 0;
            try {
               exposure = studio_.core().getExposure();
            } catch (Exception e) {
               e.printStackTrace();
            }
            studio_.events().post(new CustomSettingsEvent("exposure", "time_ms", String.valueOf(exposure)));
         }

         for (CustomSettingsEvent setting : changedSettings){
            studio_.events().post(setting);
         }
         addLog("GUIRefreshEvent");
      }

      // Subscribe to the events that are sent by the special RefreshGUI call above
      @Subscribe
      public void onSettingsChanged(CustomSettingsEvent event){
         sendJSON(event, "Settings ");
         addLog("CustomSettingsEvent " + event.getDevice() + ":"
                 + event.getProperty() + "=" + event.getValue());
      }

      @Override
      public void settingsChanged() {
         System.out.println("Setting changed");

//         PropertyChangeEvent e = new PropertyChangeEvent(1, "test",0,1);
//         acw_.propertyChange(e);

         SequenceSettings sequenceSettings = studio_.acquisitions().getAcquisitionSettings();
         CustomMDAEvent settingsEvent = new CustomMDAEvent(sequenceSettings);
         studio_.events().post(settingsEvent);
      }

      // This subscribes to the Event that is posted when a setting in the MDA window changes
      // These events are posted by the custom settingslistener implemented in a second thread in SettingsListener
      @Subscribe
      public void onCustomMDA(CustomMDAEvent event){
         sendJSON(event, "Settings ");
         addLog("CustomMDAEvent");
      }


      @Subscribe
      public void onExposureChanged(ExposureChangedEvent event) {
         sendJSON(event, "Settings ");
//         addLog("ExposureChangedEvent " + event.getNewExposureTime());
      }

      @Subscribe
      public void onStagePositionChanged(StagePositionChangedEvent event){
         long now = System.currentTimeMillis();
         System.out.println(now - lastStageTime);

         // Limit the frequency of these events
         sendJSON(event, "Hardware ");
//         addLog("StagePositionChangedEvent");
         lastStageTime = now;
      }

      @Subscribe
      public void onXYStagePositionChanged(XYStagePositionChangedEvent event){
         sendJSON(event, "Hardware ");
//         addLog("XYStagePositionChangedEvent");
      }

      @Subscribe
      public void onAcquisitionEnded(AcquisitionEndedEvent event) {
         sendJSON(event, "Acquisition ");
         addLog("AcquisitionEndedEvent");
      }


//      @Subscribe
//      public void onAcquisitionStarted(AcquisitionStartedEvent event){
//         sendJSON(event, "Acquisition ");
//         store_ = event.getDatastore();
//         store_.registerForEvents(this);
//         addLog("AcquisitionStartedEvent");
//         addLog(store_.getName());
//      }

//      @Subscribe
//      public void onDataViewerAddedEvent(DataViewerAddedEvent event) {
//         sendJSON(event, "GUI ");
//         event.getDataViewer().getDataProvider().registerForEvents(this);
//         addLog("DataViewerAddedEvent");
//      }
//
//      @Subscribe
//      public void onDataViewerWillCloseEvent(DataViewerWillCloseEvent event){
//         addLog(event.getDataViewer().getName());
//         addLog("closed");
//         event.getDataViewer().getDataProvider().unregisterForEvents(this);
//      }


      @Subscribe
      public void onAcquisitionSequenceStarted(AcquisitionSequenceStartedEvent event) {
         sendJSON(event, "Acquisition ");
         event.getDatastore().registerForEvents(this);
         addLog("AcquisitionSequenceStartedEvent");
      }

//      @Subscribe
//      public void onDataStoreClosingEvent(DatastoreClosingEvent event){
//         addLog(event.getDatastore().getName());
//         addLog("Closing");
//         event.getDatastore().unregisterForEvents(this);
//      }


      @Subscribe
      public void onDataProviderHasNewImageEvent(DataProviderHasNewImageEvent event){
         sendImage(event);
         addLog("DataProviderHasNewImageEvent");
      }

//      @Subscribe
//      public void onDataViewerAddedEvent(DataViewerAddedEvent event){
//         addLog("DataViewerAddedEvent");
//      }

      @Subscribe
      public void onLiveMode(LiveModeEvent event) {
         if (event.isOn() & liveCheckBox_.isSelected()) {
            imageListener_ = new ImageListener(studio_, this,
                    (Datastore) studio_.getSnapLiveManager().getDisplay().getDataProvider());
         } else {
            if (imageListener_ != null){
               imageListener_.close();
            }
         }
         sendJSON(event, "LiveMode ");
         addLog("LiveModeEvent");
      }


//      @Subscribe
//      public void onImageOverwritten(DefaultImageOverwrittenEvent event) {
//         sendJSON(event);
//         addLog("ImageOverwritten");
//      }


      void addLog(String eventMsg){
         String currentText = logTextArea.getText();
         String newEvent =  new Date() + " " + eventMsg;
         logTextArea.setText(newEvent + "\n" + currentText);
       }

       public void sendImage(DataProviderHasNewImageEvent event){
          Image image = event.getImage();
          ArrayList<Float> imageParams = new ArrayList<>();
          imageParams.add((float) image.getWidth());
          imageParams.add((float) image.getHeight());
          imageParams.add((float) image.getCoords().getT());
          imageParams.add((float) image.getCoords().getC());
          imageParams.add((float) image.getCoords().getZ());
          imageParams.add((float) image.getMetadata().getElapsedTimeMs(0));
          socket_.sendMore("NewImage " + imageParams);
          socket_.sendMore(String.valueOf(image.getBytesPerComponent()));
          socket_.send(image.getByteArray());
          addLog("NewImage");
       }

      public void sendJSON(Object event, String type){
            JSONObject json = new JSONObject();
            util_.serialize(event, json, 5556);
            socket_.send(type + json);
         }
      }
   }




