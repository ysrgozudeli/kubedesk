package com.kubedesk.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * A keyboard-driven quick-switcher (Ctrl/Cmd+P) — type to fuzzily filter a flat list of jump targets
 * (contexts, namespaces, resource kinds, views) and Enter to go there. It removes the app's most
 * repeated gesture: hunting the context/namespace combo boxes and the sidebar with the mouse.
 *
 * <p>Deliberately navigation-only: every {@link Item} is just a label, a category tag, and a
 * {@link Runnable} the caller supplies — the palette knows nothing about the cluster. Built on a
 * floating {@link Popup} so it needs no change to the scene's layout.
 */
public final class CommandPalette {

    /** One jump target. {@code category} is the muted tag shown on the right (e.g. "Namespace"). */
    public record Item(String category, String label, Runnable action) {
    }

    private static final double WIDTH = 540;

    private final Popup popup = new Popup();
    private final TextField search = new TextField();
    private final ListView<Item> list = new ListView<>();
    private final List<Item> all = new ArrayList<>();

    public CommandPalette() {
        search.setPromptText("Jump to a context, namespace, resource or view…");
        search.getStyleClass().add("cmd-search");

        list.getStyleClass().add("cmd-list");
        list.setPrefHeight(360);
        list.setFocusTraversable(false); // keep focus in the field; we drive selection by key
        list.setCellFactory(v -> new ItemCell());
        list.setOnMouseClicked(e -> runSelected());

        VBox root = new VBox(8, search, list);
        root.getStyleClass().add("command-palette");
        root.setPrefWidth(WIDTH);
        root.setMaxWidth(WIDTH);
        // The popup has its own scene, so our stylesheet must be attached to the content explicitly.
        var css = getClass().getResource("/styles/app.css");
        if (css != null) {
            root.getStylesheets().add(css.toExternalForm());
        }

        popup.getContent().add(root);
        popup.setAutoHide(true);  // click elsewhere closes it
        popup.setAutoFix(false);  // we position it ourselves; don't let it re-anchor

        search.textProperty().addListener((o, a, b) -> refilter());
        search.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** Populate with a fresh target list and show the palette centered near the top of the window. */
    public void show(javafx.scene.Node owner, List<Item> items) {
        if (owner.getScene() == null || owner.getScene().getWindow() == null) {
            return;
        }
        Window w = owner.getScene().getWindow();
        all.clear();
        all.addAll(items);
        search.setText("");
        refilter();

        popup.show(w);
        popup.setX(w.getX() + Math.max(0, (w.getWidth() - WIDTH) / 2));
        popup.setY(w.getY() + 96);
        Platform.runLater(search::requestFocus);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public void hide() {
        popup.hide();
    }

    private void onKey(KeyEvent ev) {
        switch (ev.getCode()) {
            case DOWN -> { move(1); ev.consume(); }
            case UP -> { move(-1); ev.consume(); }
            case ENTER -> { runSelected(); ev.consume(); }
            case ESCAPE -> { hide(); ev.consume(); }
            default -> { /* typing: let it fall through to the field */ }
        }
    }

    private void move(int delta) {
        int n = list.getItems().size();
        if (n == 0) {
            return;
        }
        int i = list.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod((i < 0 ? 0 : i) + delta, n);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void runSelected() {
        Item it = list.getSelectionModel().getSelectedItem();
        if (it == null && !list.getItems().isEmpty()) {
            it = list.getItems().get(0);
        }
        if (it == null) {
            return;
        }
        hide();
        it.action().run();
    }

    /** Case-insensitive substring match on label or category, label-prefix matches ranked first. */
    private void refilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        List<Item> prefix = new ArrayList<>();
        List<Item> contains = new ArrayList<>();
        for (Item it : all) {
            String label = it.label().toLowerCase();
            if (q.isEmpty()) {
                contains.add(it);
            } else if (label.startsWith(q)) {
                prefix.add(it);
            } else if (label.contains(q) || it.category().toLowerCase().contains(q)) {
                contains.add(it);
            }
        }
        prefix.addAll(contains);
        list.getItems().setAll(prefix);
        if (!prefix.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** A row: label on the left, muted category tag on the right. */
    private static final class ItemCell extends ListCell<Item> {
        private final Label label = new Label();
        private final Label category = new Label();
        private final HBox box;

        ItemCell() {
            label.getStyleClass().add("cmd-item-label");
            category.getStyleClass().add("cmd-category");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(10, label, spacer, category);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            label.setText(item.label());
            category.setText(item.category());
            setGraphic(box);
        }
    }
}
