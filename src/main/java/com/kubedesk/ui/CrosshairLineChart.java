package com.kubedesk.ui;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;

import java.util.List;
import java.util.function.DoubleFunction;

/**
 * A {@link LineChart} that tracks the mouse with a crosshair: a vertical and a horizontal dashed
 * guide line, plus a dot pinned to the (interpolated) point on the first series, and a tooltip
 * showing the time and value under the cursor.
 *
 * <p>Self-contained: it installs its own mouse handlers and renders the overlay into its own child
 * list. Callers only supply two formatters — one turning an x data value into the tooltip's time
 * text, one turning the interpolated y value into the tooltip's value text — so the chart stays
 * decoupled from any particular metric or time base.
 */
final class CrosshairLineChart extends LineChart<Number, Number> {

    private static final String OVERLAY_ID = "chart-crosshair";
    private static final Color GUIDE_COLOR = Color.web("#999");
    private static final Color DOT_COLOR = Color.web("#4a90d9");
    private static final double DOT_RADIUS = 4;
    private static final double READOUT_MARGIN = 8;
    private static final String READOUT_STYLE =
            "-fx-background-color: rgba(43,43,43,0.85); -fx-text-fill: white; "
                    + "-fx-padding: 3 7 3 7; -fx-background-radius: 6; -fx-font-size: 11px;";

    private final NumberAxis xAxis;
    private final NumberAxis yAxis;
    private final DoubleFunction<String> xLabeller;
    private final DoubleFunction<String> valueLabeller;

    CrosshairLineChart(NumberAxis xAxis, NumberAxis yAxis,
                       DoubleFunction<String> xLabeller,
                       DoubleFunction<String> valueLabeller) {
        super(xAxis, yAxis);
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.xLabeller = xLabeller;
        this.valueLabeller = valueLabeller;
        setOnMouseMoved(e -> showCrosshair(e.getSceneX(), e.getSceneY()));
        setOnMouseExited(e -> clearCrosshair());
    }

    private void clearCrosshair() {
        getChartChildren().removeIf(n -> OVERLAY_ID.equals(n.getId()));
    }

    private void showCrosshair(double sceneX, double sceneY) {
        clearCrosshair();
        if (getData().isEmpty()) return;
        List<XYChart.Data<Number, Number>> data = getData().get(0).getData();
        if (data.isEmpty()) return;

        // Map the mouse into each axis's own coordinate space via the scene. getValueForDisplay()
        // expects a position in the axis's local space, so this stays correct regardless of the
        // chart's padding, title and legend offsets.
        double dispX = xAxis.sceneToLocal(sceneX, sceneY).getX();
        double dispY = yAxis.sceneToLocal(sceneX, sceneY).getY();
        double plotW = xAxis.getWidth();
        double plotH = yAxis.getHeight();
        if (dispX < 0 || dispX > plotW || dispY < 0 || dispY > plotH) return;

        double dataX = xAxis.getValueForDisplay(dispX).doubleValue();
        if (Double.isNaN(dataX) || Double.isInfinite(dataX)) return;

        Bracket b = bracket(data, dataX);
        double xL = b.left.getXValue().doubleValue();
        double xR = b.right.getXValue().doubleValue();
        double t = (xR - xL) > 0 ? (dataX - xL) / (xR - xL) : 0;
        double yL = b.left.getYValue().doubleValue();
        double yR = b.right.getYValue().doubleValue();
        double val = yL + t * (yR - yL);

        // The overlay is a chart child, whose origin is the chart-content group; an axis's
        // layoutX/layoutY is the offset from that origin to the plot area, and getDisplayPosition()
        // is the pixel offset within the axis.
        double xOff = xAxis.getLayoutX();
        double yOff = yAxis.getLayoutY();
        double px = xAxis.getDisplayPosition(b.left.getXValue())
                + t * (xAxis.getDisplayPosition(b.right.getXValue()) - xAxis.getDisplayPosition(b.left.getXValue()));
        double cx = xOff + px;
        double cy = yOff + yAxis.getDisplayPosition(val);

        String timeStr = xLabeller.apply(xL + t * (xR - xL));
        String valStr = valueLabeller.apply(val);
        getChartChildren().add(buildOverlay(cx, cy, xOff, yOff, plotW, plotH, timeStr, valStr));
    }

    private Pane buildOverlay(double cx, double cy, double xOff, double yOff,
                              double plotW, double plotH, String timeStr, String valStr) {
        Line vLine = guide(cx, yOff, cx, yOff + plotH);
        Line hLine = guide(xOff, cy, xOff + plotW, cy);
        Circle dot = new Circle(cx, cy, DOT_RADIUS, DOT_COLOR);
        dot.setMouseTransparent(true);

        // Always-visible readout, pinned to the top-left of the plot area. A tooltip is unreliable
        // here because the overlay is rebuilt on every mouse move, so it rarely gets to show.
        Label readout = new Label(timeStr + "  ·  " + valStr);
        readout.setStyle(READOUT_STYLE);
        readout.setMouseTransparent(true);
        readout.setLayoutX(xOff + READOUT_MARGIN);
        readout.setLayoutY(yOff + READOUT_MARGIN);

        Pane overlay = new Pane(vLine, hLine, dot, readout);
        overlay.setMouseTransparent(true);
        overlay.setId(OVERLAY_ID);
        return overlay;
    }

    private static Line guide(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(GUIDE_COLOR);
        line.setStrokeWidth(1);
        line.getStrokeDashArray().setAll(4.0, 3.0);
        line.setMouseTransparent(true);
        return line;
    }

    /** The two data points bracketing {@code dataX}, clamped to the series ends. */
    private static Bracket bracket(List<XYChart.Data<Number, Number>> data, double dataX) {
        int n = data.size();
        if (dataX <= data.get(0).getXValue().doubleValue()) {
            return new Bracket(data.get(0), data.get(0));
        }
        if (dataX >= data.get(n - 1).getXValue().doubleValue()) {
            return new Bracket(data.get(n - 1), data.get(n - 1));
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (data.get(mid).getXValue().doubleValue() <= dataX) lo = mid;
            else hi = mid;
        }
        return new Bracket(data.get(lo), data.get(hi));
    }

    private record Bracket(XYChart.Data<Number, Number> left, XYChart.Data<Number, Number> right) {}

    /** Format an elapsed-seconds x value as a compact "Nm Ns" (or "Ns" under a minute). */
    static String formatElapsed(double seconds) {
        long total = Math.round(seconds);
        long mins = total / 60;
        long secs = total % 60;
        return mins > 0 ? mins + "m " + secs + "s" : secs + "s";
    }
}
