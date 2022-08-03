package org.micromanager.plugins.pythoneventserver;

import com.google.common.eventbus.Subscribe;
import mmcorej.org.json.JSONException;
import org.micromanager.Studio;
import org.micromanager.data.DataProviderHasNewImageEvent;
import org.micromanager.data.Datastore;
import org.micromanager.internal.utils.MustCallOnEDT;

public class ImageListener{
    private PythonEventServerFrame.ServerThread server_;
    private Datastore store_;
    private final Studio studio_;

    public ImageListener(Studio studio, PythonEventServerFrame.ServerThread serverThread, Datastore datastore) {
        studio_ = studio;
        store_ = datastore;
        server_ = serverThread;
        store_.registerForEvents(this);
    }

    @Subscribe
    public void onDataProviderHasNewImageEvent(DataProviderHasNewImageEvent event){
        server_.sendImage(event);
        server_.addLog("DataProviderHasNewImageEvent");
    }

    @MustCallOnEDT
    public void close(){
        store_.unregisterForEvents(this);
        store_ = null;
    }
}
