package com.kubedesk.service;

/**
 * A live exec session into a pod container. Write commands with {@link #send(String)} and close it
 * with {@link #close()} (which also frees the underlying connection).
 */
public interface ExecSession extends AutoCloseable {

    /** Send raw input to the container's stdin (include the trailing newline to run a command). */
    void send(String input);

    @Override
    void close();
}
