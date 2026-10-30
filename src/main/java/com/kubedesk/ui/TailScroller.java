package com.kubedesk.ui;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextArea;

/**
 * Tail-follow scrolling for a {@link TextArea}: keeps the view pinned to the newest line while you're
 * at the bottom, but the moment you scroll up to read, it stops yanking you back down — new text just
 * appends silently below. Scroll back to the bottom (or use a "jump to latest" control bound to
 * {@link #atBottomProperty()}) and it re-pins.
 *
 * <p>The key to getting this right is sampling {@link #atBottom()} <em>before</em> appending text and
 * only re-pinning if it was true — so the growth of the content (which momentarily makes the scroll
 * position look "not at the bottom") never unpins you on its own.
 */
public final class TailScroller {

    private final TextArea area;
    private final ReadOnlyBooleanWrapper atBottom = new ReadOnlyBooleanWrapper(true);
    private ScrollBar vbar;

    public TailScroller(TextArea area) {
        this.area = area;
        // The vertical scroll bar only exists once the TextArea has a skin; hook it then.
        area.skinProperty().addListener((o, a, b) -> {
            if (b != null) {
                Platform.runLater(this::hookScrollBar);
            }
        });
        if (area.getSkin() != null) {
            Platform.runLater(this::hookScrollBar);
        }
    }

    private void hookScrollBar() {
        if (vbar != null) {
            return;
        }
        for (Node n : area.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                vbar = sb;
                sb.valueProperty().addListener((o, a, b) -> refresh());
                sb.maxProperty().addListener((o, a, b) -> refresh());
                refresh();
                break;
            }
        }
    }

    private void refresh() {
        atBottom.set(computeAtBottom());
    }

    private boolean computeAtBottom() {
        // No scroll bar (content fits, or not skinned yet) counts as "at the bottom" — safe default.
        return vbar == null || vbar.getMax() <= 0 || vbar.getValue() >= vbar.getMax() - 1e-4;
    }

    /** Whether the view is currently scrolled to (or within a hair of) the bottom. */
    public boolean atBottom() {
        return computeAtBottom();
    }

    /**
     * Append text and keep the view pinned to the bottom only if it was already there. Call this
     * instead of {@code area.appendText(...)} + an unconditional scroll.
     */
    public void appendFollowing(String text) {
        boolean wasAtBottom = computeAtBottom();
        area.appendText(text);
        if (wasAtBottom) {
            scrollToBottom();
        }
    }

    /** Force the view to the newest line and re-pin (e.g. a fresh load, or a "jump to latest" click). */
    public void scrollToBottom() {
        atBottom.set(true);
        // Defer so layout recomputes the scroll range from the new content first.
        Platform.runLater(() -> {
            area.setScrollTop(Double.MAX_VALUE);
            if (vbar != null) {
                vbar.setValue(vbar.getMax());
            }
        });
    }

    /** True while the view is pinned at the bottom — bind a "jump to latest" control to its negation. */
    public ReadOnlyBooleanProperty atBottomProperty() {
        return atBottom.getReadOnlyProperty();
    }
}
