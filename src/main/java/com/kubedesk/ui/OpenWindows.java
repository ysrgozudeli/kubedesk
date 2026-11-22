package com.kubedesk.ui;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Tracks every detached (log / shell) window that is currently open. Owned by {@link MainView},
 * which passes itself to each window it creates. All access is on the FX thread.
 */
public final class OpenWindows {

    public enum Kind { LOGS, SHELL }

    public enum State { LIVE, IDLE, ENDED }

    /** What each detached window exposes to the registry. */
    public interface Handle {
        Kind kind();
        String title();
        String subtitle();
        ReadOnlyObjectProperty<State> stateProperty();
        void focus();
        void close();
    }

    private final ObservableList<Handle> windows = FXCollections.observableArrayList();

    public ObservableList<Handle> windows() {
        return windows;
    }

    public void register(Handle h) {
        if (!windows.contains(h)) {
            windows.add(h);
        }
    }

    public void unregister(Handle h) {
        windows.remove(h);
    }
}
