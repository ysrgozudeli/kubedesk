package com.kubedesk.prefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User preferences persisted across restarts (serialized to ~/.kubedesk/prefs.json).
 * Plain mutable fields keep JSON (de)serialization trivial.
 */
public class Settings {

    /** Path to a user-chosen kube.conf, or {@code null} to use default discovery. */
    public String kubeConfigPath;

    /** Last selected context / namespace, restored on launch. */
    public String lastContext;
    public String lastNamespace;

    /** Namespace last used per context (context name -> namespace), so switching contexts restores it. */
    public Map<String, String> namespaceByContext = new HashMap<>();

    /** Hidden table columns, keyed by ResourceKind name -> list of hidden column headers. */
    public Map<String, List<String>> hiddenColumns = new HashMap<>();

    /** Whether first-run column defaults (e.g. hide verbose pod columns) have been seeded. */
    public boolean columnDefaultsApplied = false;

    /** Check GitHub for a newer release on startup. Set false in prefs.json to disable. */
    public boolean updateCheckEnabled = true;

    /** A version the user chose to skip; the update banner won't reappear for it. */
    public String skippedVersion;

    /** Port-forwards active at last exit, offered for restore on launch. */
    public List<PortForwardPref> portForwards = new ArrayList<>();

    /** A saved port-forward. */
    public record PortForwardPref(String context, String namespace, String pod,
                                  int remotePort, int localPort) {
    }
}
