package com.kubedesk.service;

/**
 * A live port-forward tunnel from {@code localhost:<localPort>} to a pod's port, over the
 * Kubernetes API. Close it with {@link #close()} to stop the tunnel and free the connection.
 */
public interface PortForward extends AutoCloseable {

    String namespace();

    String pod();

    int remotePort();

    /** The local port the tunnel is listening on (chosen automatically if one wasn't requested). */
    int localPort();

    /** Whether the tunnel is still up. */
    boolean isAlive();

    @Override
    void close();
}
