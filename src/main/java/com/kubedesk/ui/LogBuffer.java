package com.kubedesk.ui;

import javafx.scene.control.TextArea;

import java.util.stream.Collectors;

/**
 * Holds the full, unfiltered log text for a {@link TextArea} and projects it through an optional
 * case-insensitive "filter lines" query, driving a {@link TailScroller} so follow + pin-to-bottom keep
 * working. Keeping the raw text here (rather than in the TextArea) means a line filter only hides lines
 * from the view — Copy/Save still get the complete log via {@link #raw()}.
 *
 * <p>Shared by the inline Logs tab and the detached {@link LogWindow} so the buffer/filter logic lives
 * in one place.
 */
public final class LogBuffer {

    private final TextArea area;
    private final TailScroller tail;
    private final StringBuilder raw = new StringBuilder();
    private String filter = "";

    public LogBuffer(TextArea area, TailScroller tail) {
        this.area = area;
        this.tail = tail;
    }

    /** Replace the whole buffer (a one-shot load) and render it through the current filter. */
    public void setText(String full) {
        raw.setLength(0);
        raw.append(full == null ? "" : full);
        render();
    }

    /** Append streamed text. With no filter this appends directly (smooth follow); with a filter active
     *  it re-renders the filtered view so only matching lines show. */
    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        raw.append(chunk);
        if (filter.isEmpty()) {
            tail.appendFollowing(chunk);
        } else {
            render();
        }
    }

    /** Set the case-insensitive line filter (blank shows everything) and re-render. */
    public void setFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase();
        render();
    }

    /** Clear the buffer and the view. */
    public void clear() {
        raw.setLength(0);
        area.clear();
    }

    /** The complete, unfiltered log text — used by Copy and Save so filtering never loses data. */
    public String raw() {
        return raw.toString();
    }

    private void render() {
        if (filter.isEmpty()) {
            area.setText(raw.toString());
        } else {
            String shown = raw.toString().lines()
                    .filter(line -> line.toLowerCase().contains(filter))
                    .collect(Collectors.joining("\n"));
            area.setText(shown);
        }
        tail.scrollToBottom();
    }
}
