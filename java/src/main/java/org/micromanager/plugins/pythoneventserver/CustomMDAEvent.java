package org.micromanager.plugins.pythoneventserver;

import org.micromanager.acquisition.SequenceSettings;

/**
 * Class that is used to relay Events in the MDA window of Micro-Manager
 */


public class CustomMDAEvent {
    private final SequenceSettings newSettings_;

    public CustomMDAEvent(SequenceSettings newSettings) {
        newSettings_ = newSettings;
    }

    public SequenceSettings getSettings() {
        return newSettings_;
    }

}
