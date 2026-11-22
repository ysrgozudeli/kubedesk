package com.kubedesk.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import com.kubedesk.ui.OpenWindows.Handle;
import com.kubedesk.ui.OpenWindows.State;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A horizontal strip of chips, one per open detached window (log or shell). Sits just above the
 * status bar in the main window. Hidden entirely when no windows are open.
 */
public final class OpenWindowsBar extends HBox {

    private final OpenWindows registry;
    private final ObservableList<Handle> windows;
    private final HBox chipBox;
    private final ScrollPane scrollPane;
    private final Button closeAllBtn;
    /** Chip + state listener per window, so chips are added/removed incrementally and listeners detached. */
    private final Map<Handle, Chip> chips = new HashMap<>();

    private record Chip(HBox node, ChangeListener<State> listener) { }

    public OpenWindowsBar(OpenWindows registry) {
        this.registry = registry;
        this.windows = registry.windows();

        getStyleClass().add("open-windows-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new Insets(4, 12, 4, 12));

        chipBox = new HBox(6);
        chipBox.setAlignment(Pos.CENTER_LEFT);
        chipBox.setPadding(new Insets(0, 4, 0, 0));

        scrollPane = new ScrollPane(chipBox);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefViewportWidth(0);
        HBox.setHgrow(scrollPane, Priority.ALWAYS);
        // Strip the scrollpane's default padding/border so it blends into the bar.
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");

        closeAllBtn = new Button("Close all");
        closeAllBtn.getStyleClass().add("icon-button");
        closeAllBtn.setTooltip(new Tooltip("Close all detached windows"));
        closeAllBtn.setOnAction(e -> {
            for (Handle h : new ArrayList<>(windows)) { // close() unregisters -> mutates the list
                h.close();
            }
        });

        getChildren().addAll(scrollPane, closeAllBtn);

        // Visibility: show only when at least one window is open.
        visibleProperty().bind(Bindings.isNotEmpty(windows));
        managedProperty().bind(visibleProperty());

        // Close-all button: only visible when >= 2 windows.
        closeAllBtn.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> windows.size() >= 2, windows));
        closeAllBtn.managedProperty().bind(closeAllBtn.visibleProperty());

        windows.addListener((ListChangeListener<Handle>) change -> {
            while (change.next()) {
                for (Handle h : change.getRemoved()) {
                    Chip c = chips.remove(h);
                    if (c != null) {
                        h.stateProperty().removeListener(c.listener());
                        chipBox.getChildren().remove(c.node());
                    }
                }
                for (Handle h : change.getAddedSubList()) {
                    Chip c = buildChip(h);
                    chips.put(h, c);
                    chipBox.getChildren().add(c.node());
                }
            }
        });
        for (Handle h : windows) {
            Chip c = buildChip(h);
            chips.put(h, c);
            chipBox.getChildren().add(c.node());
        }
    }

    private Chip buildChip(Handle h) {
        String glyph = h.kind() == OpenWindows.Kind.LOGS ? "📜" : "⌨";

        Label dot = new Label("●");
        dot.getStyleClass().add("status-dot");
        dot.getStyleClass().add(dotClass(h.stateProperty().get()));
        dot.setTooltip(new Tooltip(stateLabel(h.stateProperty().get())));
        ChangeListener<State> listener = (obs, old, s) -> {
            dot.getStyleClass().removeAll("dot-ok", "dot-warn", "dot-bad", "dot-neutral");
            dot.getStyleClass().add(dotClass(s));
            dot.setTooltip(new Tooltip(stateLabel(s)));
        };
        h.stateProperty().addListener(listener);

        Label name = new Label(h.title());
        name.setMaxWidth(160);

        Button close = new Button("✕");
        close.getStyleClass().add("icon-button");
        close.setTooltip(new Tooltip("Close window"));
        close.setOnAction(e -> h.close());
        // Don't let the click bubble to the chip, whose handler would focus (re-show) the closed window.
        close.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);

        HBox chip = new HBox(6, new Label(glyph), dot, name, close);
        chip.getStyleClass().add("ow-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(chip, new Tooltip(h.kind().name().toLowerCase() + " · " + h.subtitle()));
        chip.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.MIDDLE) {
                h.close();
            } else {
                h.focus();
            }
        });

        return new Chip(chip, listener);
    }

    private static String dotClass(State s) {
        return switch (s) {
            case LIVE -> "dot-ok";
            case ENDED -> "dot-bad";
            default -> "dot-neutral";
        };
    }

    private static String stateLabel(State s) {
        return switch (s) {
            case LIVE -> "Live";
            case ENDED -> "Ended";
            default -> "Idle";
        };
    }
}
