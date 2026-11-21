package com.kubedesk.service;

/**
 * A live exec session into a pod container. Write commands with {@link #send(String)} and close it
 * with {@link #close()} (which also frees the underlying connection).
 *
 * <p>Two flavours exist, both implemented by the same class: a <em>command console</em> (no TTY,
 * you send whole lines and read the echoed transcript back) and a <em>TTY</em> (a real PTY with a
 * live prompt, where {@link #sendRaw} forwards individual keystrokes and {@link #resize} tells the
 * remote terminal its size so interactive apps like {@code top} or {@code vim} lay out correctly).
 * {@link #isTty()} tells a caller which flavour it got.
 */
public interface ExecSession extends AutoCloseable {

    /** Send raw input to the container's stdin (include the trailing newline to run a command). */
    void send(String input);

    /** The shell that answered (e.g. {@code /bin/sh}); shown to the user on connect. */
    default String shell() {
        return "/bin/sh";
    }

    /**
     * Send a raw byte buffer to the PTY — a single keystroke or escape sequence. Only meaningful on a
     * TTY session; on a command console it falls back to {@link #send} of the bytes as UTF-8 text.
     */
    default void sendRaw(byte[] data) {
        send(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Tell the remote terminal the new viewport size (columns × rows) so interactive programs redraw.
     * A no-op on a command console (there is no terminal to resize).
     */
    default void resize(int columns, int rows) {
        // no TTY, nothing to resize
    }

    /** Whether this session is backed by a real PTY (a true terminal), or a plain command console. */
    default boolean isTty() {
        return false;
    }

    @Override
    void close();
}
