package com.kubedesk.mcp;

import com.kubedesk.service.ResourceKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The activity channel between the embedded {@link KubeDeskMcpServer} and the KubeDesk UI — the
 * "live mirror". The server records one {@link Event} per tool call; the UI subscribes to reflect
 * each call (a status-bar line, and optionally revealing the resource the agent read) and the MCP
 * panel renders the recent-call list.
 *
 * <p>Events are already reduced to display-ready strings here — no cluster objects or Jackson nodes
 * cross this boundary, so the UI never touches the transport layer. Everything is read-only today;
 * this same channel is the rail a future write-approval gate will ride on.
 *
 * <p>Thread-safe: {@link #record} is called on the server's HTTP worker threads, while listeners
 * (the UI) read/subscribe on the JavaFX thread.
 */
public final class McpActivity {

    public enum Status { OK, ERROR }

    /**
     * One completed MCP tool call. {@code navKind}/{@code navName}/{@code navNamespace} name a
     * resource the UI can reveal when "follow" is on, or are {@code null} for calls with no single
     * resource to show (e.g. {@code list_namespaces}).
     */
    public record Event(long seq, long epochMillis, String tool, String summary,
                        Status status, String outcome,
                        ResourceKind navKind, String navName, String navNamespace) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    /** A subscriber to tool-call events. Implementations must be quick and must not block. */
    public interface Listener {
        void onActivity(Event e);
    }

    /** Keep the recent tail bounded — this is a live view, not an audit trail. */
    private static final int CAPACITY = 200;

    private final Deque<Event> recent = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long seq = 0;

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Append an event (evicting the oldest past {@link #CAPACITY}) and notify every listener. */
    public Event record(String tool, String summary, Status status, String outcome,
                        ResourceKind navKind, String navName, String navNamespace) {
        Event e;
        synchronized (this) {
            e = new Event(++seq, System.currentTimeMillis(), tool, summary, status, outcome,
                    navKind, navName, navNamespace);
            recent.addLast(e);
            while (recent.size() > CAPACITY) {
                recent.removeFirst();
            }
        }
        for (Listener l : listeners) {
            try {
                l.onActivity(e);
            } catch (RuntimeException ignored) {
                // A misbehaving listener must never break the server's response path.
            }
        }
        return e;
    }

    /** A snapshot of the recent events, oldest first. */
    public synchronized List<Event> snapshot() {
        return new ArrayList<>(recent);
    }
}
