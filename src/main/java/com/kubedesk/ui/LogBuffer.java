package com.kubedesk.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Holds the log text for a {@link TextArea} and projects it through the active view options — a
 * case-insensitive "filter lines" query and a set of visible severity levels — driving a
 * {@link TailScroller} so follow + pin-to-bottom keep working. View options only hide lines on screen;
 * Copy/Save get the buffer via {@link #raw()}.
 *
 * <p>Lines with no recognisable level (stack-trace lines, wrapped messages) inherit the previous line's
 * level, so an exception stays attached to the ERROR that caused it. Per-level counts are kept
 * incrementally as text streams in.
 *
 * <p><b>Heavy streams.</b> A flood of lines would otherwise grow the buffer without bound and make every
 * redraw slower. When the stream is heavy (see {@link #HEAVY_RATE} / {@link #HEAVY_LINES}) the buffer
 * switches to a protective mode: it keeps only the latest {@link #RETAIN_LINES} lines (older ones are
 * dropped), and updates the screen a few times a second instead of per chunk. The user can then narrow to
 * errors, pause the view, or switch to a raw view with all processing off. While heavy, a level or text filter
 * is applied <em>as lines arrive</em> (into a separate "kept" window), so the retained lines are the latest
 * matching ones — an errors-only view reaches much further back than the last few seconds of the flood. The
 * trade-off: lines filtered out while heavy are not kept. Heavy mode is sticky until the
 * buffer is cleared or reloaded, and {@link #isHeavy()} drives a banner in the UI.
 *
 * <p>Shared by the inline Logs tab and the detached {@link LogWindow} so the buffer/filter logic lives in
 * one place.
 */
public final class LogBuffer {

    /** Sustained lines per second that marks a stream as heavy. */
    public static final int HEAVY_RATE = 500;
    /** Lines held in the buffer that marks it as heavy regardless of rate. */
    public static final int HEAVY_LINES = 20_000;
    /** Lines kept once heavy; older ones are dropped. */
    public static final int RETAIN_LINES = 5_000;
    /** In heavy mode, trim only after this much overshoot so we don't trim on every chunk. */
    private static final int TRIM_SLACK = 1_000;
    /** The first seconds after a (re)load carry the tail backlog, not a real rate — don't judge those. */
    private static final long RATE_GRACE_NS = 3_000_000_000L;
    private static final long RATE_WINDOW_NS = 2_000_000_000L;
    private static final int FLUSH_MS = 250;

    private final TextArea area;
    private final TailScroller tail;
    private final StringBuilder raw = new StringBuilder();
    private String filter = "";

    private final Set<LogLevel> visible = EnumSet.allOf(LogLevel.class);

    // incremental per-level counting (complete lines only; a trailing partial line waits for its newline)
    private final int[] counts = new int[LogLevel.values().length];
    private final StringBuilder carry = new StringBuilder();
    private LogLevel countLast = null;
    private int linesInRaw;
    private int seenLines; // every complete line counted, including ones later dropped

    // heavy + narrowed: only matching lines are kept (the filter runs at ingestion)
    private final StringBuilder kept = new StringBuilder();
    private int keptLines;
    private LogLevel keptLast = null;

    // heavy-stream protection
    private boolean heavy;
    private boolean rawMode;   // heavy only: no level detection, no filtering — just the retained tail
    private boolean paused;    // view frozen; lines still buffered
    private int linesWhilePaused;
    private int droppedLines;
    private double lastRate;
    private long epochNs = System.nanoTime();
    private long winStartNs;
    private int winLines;
    private final PauseTransition flushTimer = new PauseTransition(Duration.millis(FLUSH_MS));
    private boolean flushScheduled;

    private String lastRendered = "";
    private int shownLines;
    private int totalLines;
    private final List<Runnable> listeners = new ArrayList<>();

    public LogBuffer(TextArea area, TailScroller tail) {
        this.area = area;
        this.tail = tail;
        flushTimer.setOnFinished(e -> {
            flushScheduled = false;
            if (paused) {
                notifyChange();
            } else {
                render(true);
            }
        });
    }

    /** Replace the whole buffer (a one-shot load) and render it through the current view options. */
    public void setText(String full) {
        raw.setLength(0);
        resetState();
        String text = full == null ? "" : full;
        raw.append(text);
        count(text);
        render();
    }

    /** Append streamed text. Normally this appends directly (smooth follow) or re-renders the projected
     *  view; on a heavy stream it caps the buffer and batches screen updates instead. */
    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        raw.append(chunk);
        int newLines = count(chunk);
        if (paused) {
            linesWhilePaused += newLines;
        }
        updateHeavy(newLines);

        if (heavy) {
            trimIfNeeded();
            scheduleFlush();
            return;
        }
        if (!isNarrowed()) {
            tail.appendFollowing(chunk);
            notifyChange();
        } else {
            render(true);
        }
    }

    /** Set the case-insensitive line filter (blank shows everything) and re-render. */
    public void setFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase();
        if (!filter.isEmpty()) {
            rawMode = false; // asking for a filter means you want it applied
        }
        if (heavy) {
            rebuildKept();
        }
        render();
    }

    /** Choose which severity levels are shown (a copy is kept) and re-render. */
    public void setVisibleLevels(Set<LogLevel> levels) {
        visible.clear();
        visible.addAll(levels);
        if (visible.size() != LogLevel.values().length) {
            rawMode = false;
        }
        if (heavy) {
            rebuildKept();
        }
        render();
    }

    public Set<LogLevel> visibleLevels() {
        return EnumSet.copyOf(visible);
    }

    /** Clear the buffer and the view (also ends heavy mode). */
    public void clear() {
        raw.setLength(0);
        resetState();
        shownLines = 0;
        totalLines = 0;
        lastRendered = "";
        area.clear();
        notifyChange();
    }

    /** The log text held in the buffer — complete unless heavy mode dropped older lines ({@link #droppedLines()}). */
    public String raw() {
        return raw.toString();
    }

    /** Whether the buffer holds any log lines yet (drives the "no log lines" empty state). */
    public boolean hasContent() {
        return !raw.isEmpty();
    }

    /** The current text filter query (empty when everything is shown). */
    public String filter() {
        return filter;
    }

    /** True when any view option (text filter, level selection) is narrowing what's shown. */
    public boolean isNarrowed() {
        return !rawMode && (!filter.isEmpty() || visible.size() != LogLevel.values().length);
    }

    /** Lines currently shown / in total, as of the last render (meaningful while {@link #isNarrowed()}). */
    public int shownLineCount() {
        return shownLines;
    }

    public int totalLineCount() {
        return totalLines;
    }

    /** How many complete lines were classified at {@code level}. */
    public int countOf(LogLevel level) {
        return counts[level.ordinal()];
    }

    /** True once at least one line with a recognisable severity has been seen. */
    public boolean hasLevels() {
        for (LogLevel l : LogLevel.values()) {
            if (l != LogLevel.NONE && counts[l.ordinal()] > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- heavy-stream controls ------------------------------------------------------------

    /** True while the stream is heavy and the buffer is capped / batched. */
    public boolean isHeavy() {
        return heavy;
    }

    /** The most recently measured stream rate in lines per second (0 until measured). */
    public double lastRate() {
        return lastRate;
    }

    /** Lines dropped so far because the stream was heavy. */
    public int droppedLines() {
        return droppedLines;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Freeze the view (lines keep buffering) or resume and jump to the latest. */
    public void setPaused(boolean pause) {
        if (this.paused == pause) {
            return;
        }
        this.paused = pause;
        if (pause) {
            linesWhilePaused = 0;
            notifyChange();
        } else {
            linesWhilePaused = 0;
            render();
        }
    }

    public int linesWhilePaused() {
        return linesWhilePaused;
    }

    public boolean isRawMode() {
        return rawMode;
    }

    /** Raw view: show the retained tail with no filtering and no level detection (cheapest). */
    public void setRawMode(boolean raw) {
        this.rawMode = raw;
        if (heavy && !raw) {
            rebuildKept();
        }
        render();
    }

    /** Be told whenever the view or counts change (to refresh "showing X of Y", the level menu, the banner). */
    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyChange() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    private void resetState() {
        java.util.Arrays.fill(counts, 0);
        carry.setLength(0);
        countLast = null;
        linesInRaw = 0;
        seenLines = 0;
        kept.setLength(0);
        keptLines = 0;
        keptLast = null;
        heavy = false;
        rawMode = false;
        paused = false;
        linesWhilePaused = 0;
        droppedLines = 0;
        lastRate = 0;
        epochNs = System.nanoTime();
        winStartNs = 0;
        winLines = 0;
        flushTimer.stop();
        flushScheduled = false;
    }

    // ---- heavy detection / capping --------------------------------------------------------

    private void updateHeavy(int newLines) {
        if (heavy) {
            return;
        }
        long now = System.nanoTime();
        if (now - epochNs >= RATE_GRACE_NS) {
            if (winStartNs == 0) {
                winStartNs = now;
                winLines = 0;
            }
            winLines += newLines;
            long span = now - winStartNs;
            if (span >= RATE_WINDOW_NS) {
                lastRate = winLines * 1e9 / span;
                winStartNs = now;
                winLines = 0;
                if (lastRate >= HEAVY_RATE) {
                    heavy = true;
                }
            }
        }
        if (linesInRaw >= HEAVY_LINES) {
            heavy = true;
        }
        if (heavy) { // just flipped: start filtering at ingestion from the lines we already hold
            rebuildKept();
        }
    }

    /** Drop the oldest lines so at most about {@link #RETAIN_LINES} remain (in batches). */
    private void trimIfNeeded() {
        if (linesInRaw <= RETAIN_LINES + TRIM_SLACK) {
            return;
        }
        int toDrop = linesInRaw - RETAIN_LINES;
        int idx = -1;
        for (int i = 0; i < toDrop; i++) {
            idx = raw.indexOf("\n", idx + 1);
            if (idx < 0) {
                return;
            }
        }
        raw.delete(0, idx + 1);
        linesInRaw -= toDrop;
        droppedLines += toDrop;
        selectionSafeReset();
    }

    private void trimKeptIfNeeded() {
        if (keptLines <= RETAIN_LINES + TRIM_SLACK) {
            return;
        }
        int toDrop = keptLines - RETAIN_LINES;
        int idx = -1;
        for (int i = 0; i < toDrop; i++) {
            idx = kept.indexOf("\n", idx + 1);
            if (idx < 0) {
                return;
            }
        }
        kept.delete(0, idx + 1);
        keptLines -= toDrop;
    }

    /** Does a line at {@code lvl} pass the current text + level filters? */
    private boolean matches(String line, LogLevel lvl) {
        return visible.contains(lvl) && (filter.isEmpty() || line.toLowerCase().contains(filter));
    }

    /** Re-derive the "kept" window from the lines currently held (used when heavy starts or the filter changes). */
    private void rebuildKept() {
        kept.setLength(0);
        keptLines = 0;
        keptLast = null;
        if (!isNarrowed()) {
            return;
        }
        LogLevel last = null;
        for (String line : (Iterable<String>) raw.toString().lines()::iterator) {
            LogLevel d = LogLevel.detect(line);
            if (d != null) {
                last = d;
            }
            LogLevel lvl = d != null ? d : last != null ? last : LogLevel.NONE;
            if (matches(line, lvl)) {
                kept.append(line).append('\n');
                keptLines++;
            }
        }
        keptLast = last;
    }

    private void selectionSafeReset() {
        lastRendered = ""; // the buffer changed under the view; force the next flush to redraw
    }

    private void scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            flushTimer.playFromStart();
        }
    }

    // ---- counting -------------------------------------------------------------------------

    /** Count complete lines in {@code text}; returns how many there were. */
    private int count(String text) {
        carry.append(text);
        int lines = 0;
        int idx;
        while ((idx = carry.indexOf("\n")) >= 0) {
            lines++;
            if (!rawMode) { // raw mode skips level detection entirely
                String line = carry.substring(0, idx);
                LogLevel l = LogLevel.detect(line);
                if (l != null) {
                    countLast = l;
                }
                LogLevel lvl = l != null ? l : countLast != null ? countLast : LogLevel.NONE;
                counts[lvl.ordinal()]++;
                if (heavy && isNarrowed()) { // filter at ingestion: keep only matching lines
                    if (l != null) {
                        keptLast = l;
                    }
                    if (matches(line, l != null ? l : keptLast != null ? keptLast : LogLevel.NONE)) {
                        kept.append(line).append('\n');
                        keptLines++;
                    }
                }
            }
            carry.delete(0, idx + 1);
        }
        linesInRaw += lines;
        seenLines += lines;
        if (heavy) {
            trimKeptIfNeeded();
        }
        return lines;
    }

    // ---- rendering ------------------------------------------------------------------------

    private void render() {
        render(false);
    }

    /** @param streamed true when triggered by new streamed text (keep the reader's scroll position) */
    private void render(boolean streamed) {
        if (paused) {
            notifyChange(); // the view stays frozen
            return;
        }
        if (!isNarrowed()) {
            String text = raw.toString();
            if (streamed) {
                setTextKeepingPosition(text);
            } else {
                area.setText(text);
                tail.scrollToBottom();
            }
            lastRendered = "";
            shownLines = totalLines = linesInRaw;
            notifyChange();
            return;
        }

        if (heavy) { // filtered at ingestion: the kept window IS the filtered view
            String text = kept.toString();
            shownLines = keptLines;
            totalLines = seenLines;
            if (streamed) {
                if (!text.equals(lastRendered)) {
                    setTextKeepingPosition(text);
                }
            } else {
                area.setText(text);
                tail.scrollToBottom();
            }
            lastRendered = text;
            notifyChange();
            return;
        }

        List<String> flat = new ArrayList<>();
        LogLevel last = null;
        int total = 0;
        for (String line : (Iterable<String>) raw.toString().lines()::iterator) {
            total++;
            LogLevel d = LogLevel.detect(line);
            if (d != null) {
                last = d;
            }
            LogLevel lvl = d != null ? d : last != null ? last : LogLevel.NONE;
            if (visible.contains(lvl) && (filter.isEmpty() || line.toLowerCase().contains(filter))) {
                flat.add(line);
            }
        }
        shownLines = flat.size();
        totalLines = total;

        String text = String.join("\n", flat);
        if (streamed) {
            // New lines that are all filtered out change nothing visible: don't redraw (that would also
            // drop the selection and the scroll position).
            if (!text.equals(lastRendered)) {
                setTextKeepingPosition(text);
            }
        } else {
            area.setText(text);
            tail.scrollToBottom();
        }
        lastRendered = text;
        notifyChange();
    }

    /** Replace the text; stay pinned if the reader was at the bottom, otherwise keep their scroll position. */
    private void setTextKeepingPosition(String text) {
        boolean wasAtBottom = tail.atBottom();
        double scrollTop = area.getScrollTop();
        area.setText(text);
        if (wasAtBottom) {
            tail.scrollToBottom();
        } else {
            Platform.runLater(() -> area.setScrollTop(scrollTop));
        }
    }
}
