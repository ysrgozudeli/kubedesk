package com.kubedesk.ui;

import com.kubedesk.service.Dtos.TopoEdge;
import com.kubedesk.service.Dtos.TopoNode;
import com.kubedesk.service.Dtos.TopologyData;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A lightweight, dependency-free topology renderer. Nodes are laid out in columns by tier
 * (Ingress | Service | Workload) and connected with bezier edges. Nodes are clickable, and the
 * canvas supports panning and Ctrl+scroll zoom.
 */
public class TopologyView extends ScrollPane {

    private static final double NODE_W = 210;
    private static final double NODE_H = 62;
    private static final double COL_GAP = 120;
    private static final double ROW_GAP = 22;
    private static final double MARGIN = 28;

    private final Pane canvas = new Pane();
    private final Scale scale = new Scale(1, 1);

    // For click-to-highlight of edges (and their endpoint nodes).
    private final Map<String, VBox> nodeBoxes = new HashMap<>();
    private final List<CubicCurve> edgeVisuals = new ArrayList<>();

    public TopologyView() {
        canvas.getTransforms().add(scale);
        Group zoomGroup = new Group(canvas);
        setContent(zoomGroup);
        setPannable(true);
        getStyleClass().add("topology");

        addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown()) {
                double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
                double s = clamp(scale.getX() * factor, 0.3, 2.5);
                scale.setX(s);
                scale.setY(s);
                e.consume();
            }
        });
    }

    public void resetZoom() {
        scale.setX(1);
        scale.setY(1);
    }

    public void render(TopologyData data, Consumer<TopoNode> onNodeClick) {
        canvas.getChildren().clear();
        nodeBoxes.clear();
        edgeVisuals.clear();
        // Click empty canvas to clear any edge highlight.
        canvas.setOnMouseClicked(e -> clearHighlight());
        if (data == null || data.nodes().isEmpty()) {
            Label empty = new Label("No Ingresses, Services or workloads in this namespace.");
            empty.setLayoutX(MARGIN);
            empty.setLayoutY(MARGIN);
            canvas.getChildren().add(empty);
            return;
        }

        // Assign each node a column (by tier) and a row (running index within its tier).
        Map<String, double[]> pos = new HashMap<>();
        Map<Integer, Integer> rowIndex = new HashMap<>();
        for (TopoNode n : data.nodes()) {
            int i = rowIndex.merge(n.tier(), 1, Integer::sum) - 1;
            double x = MARGIN + n.tier() * (NODE_W + COL_GAP);
            double y = MARGIN + i * (NODE_H + ROW_GAP);
            pos.put(n.id(), new double[]{x, y});
        }

        // Edges first, so nodes render on top of them.
        for (TopoEdge e : data.edges()) {
            double[] from = pos.get(e.from());
            double[] to = pos.get(e.to());
            if (from == null || to == null) {
                continue;
            }
            double x1 = from[0] + NODE_W;
            double y1 = from[1] + NODE_H / 2;
            double x2 = to[0];
            double y2 = to[1] + NODE_H / 2;
            double c1x = x1 + COL_GAP * 0.6;
            double c2x = x2 - COL_GAP * 0.6;

            CubicCurve curve = new CubicCurve(x1, y1, c1x, y1, c2x, y2, x2, y2);
            curve.setFill(null);
            curve.getStyleClass().add("topo-edge");

            // A thick, transparent curve on top makes the thin line easy to click.
            CubicCurve hit = new CubicCurve(x1, y1, c1x, y1, c2x, y2, x2, y2);
            hit.setFill(null);
            hit.setStroke(Color.TRANSPARENT);
            hit.setStrokeWidth(14);
            hit.setStyle("-fx-cursor: hand;");
            final String fromId = e.from();
            final String toId = e.to();
            hit.setOnMouseClicked(ev -> {
                highlightEdge(curve, fromId, toId);
                ev.consume();
            });

            edgeVisuals.add(curve);
            canvas.getChildren().addAll(curve, hit);
        }

        for (TopoNode n : data.nodes()) {
            double[] p = pos.get(n.id());
            VBox box = buildNode(n, onNodeClick);
            box.setLayoutX(p[0]);
            box.setLayoutY(p[1]);
            nodeBoxes.put(n.id(), box);
            canvas.getChildren().add(box);
        }
    }

    /** Highlight a clicked edge and the two nodes it connects; clears any prior highlight. */
    private void highlightEdge(CubicCurve edge, String fromId, String toId) {
        clearHighlight();
        edge.getStyleClass().add("topo-edge-selected");
        VBox from = nodeBoxes.get(fromId);
        VBox to = nodeBoxes.get(toId);
        if (from != null) {
            from.getStyleClass().add("topo-node-selected");
        }
        if (to != null) {
            to.getStyleClass().add("topo-node-selected");
        }
    }

    private void clearHighlight() {
        edgeVisuals.forEach(c -> c.getStyleClass().remove("topo-edge-selected"));
        nodeBoxes.values().forEach(b -> b.getStyleClass().remove("topo-node-selected"));
    }

    private VBox buildNode(TopoNode n, Consumer<TopoNode> onNodeClick) {
        Label kindLabel = new Label(n.kind().toUpperCase());
        kindLabel.getStyleClass().add("topo-kind");
        Label nameLabel = new Label(n.name());
        nameLabel.getStyleClass().add("topo-name");

        VBox box = new VBox(1, kindLabel, nameLabel);
        if (n.subtitle() != null && !n.subtitle().isBlank()) {
            Label sub = new Label(n.subtitle());
            sub.getStyleClass().add("topo-sub");
            box.getChildren().add(sub);
        }
        box.setPrefSize(NODE_W, NODE_H);
        box.setMinSize(NODE_W, NODE_H);
        box.setMaxSize(NODE_W, NODE_H);
        box.getStyleClass().addAll("topo-node", "topo-" + n.kind().toLowerCase());
        box.setOnMouseClicked(e -> onNodeClick.accept(n));
        return box;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
