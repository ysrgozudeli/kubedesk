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
        /** Identity for de-duplication: one window per view kind per pod. See {@link OpenWindows#key}. */
        String key();
        ReadOnlyObjectProperty<State> stateProperty();
        void focus();
        void close();
    }

    private final ObservableList<Handle> windows = FXCollections.observableArrayList();

    public ObservableList<Handle> windows() {
        return windows;
    }

    /** One detached window per view (logs / shell) per pod. */
    public static String key(Kind kind, String ctx, String namespace, String pod) {
        return kind + "|" + ctx + "|" + namespace + "|" + pod;
    }

    /** If a window with this key is already open, bring it to the front and return true. */
    public boolean focusExisting(String key) {
        for (Handle h : windows) {
            if (h.key().equals(key)) {
                h.focus();
                return true;
            }
        }
        return false;
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
