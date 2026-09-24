/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A ring of slices with the total in the middle.
 *
 * <p>Drawn with arcs rather than JavaFX's pie chart: the pie chart styles its
 * legend and labels for a light surface, places labels where they collide on a
 * small slice, and has no hover. This one has a surface gap between slices,
 * grows the slice under the pointer, says in the middle what it is, and tells
 * the window which one was clicked. The legend beside it is the window's, so
 * identity is never colour alone.
 */
final class DonutChart extends Region {

    /**
     * One slice.
     *
     * @param key  what the window gets back on hover and click
     * @param text the size as the centre should say it
     */
    record Slice(String label, double value, Color color, Object key, String text) {
    }

    /** The surface gap between slices, in pixels along the ring. */
    private static final double GAP = 2.5;

    private final Group ring = new Group();
    private final Label centerValue = new Label();
    private final Label centerCaption = new Label();
    private final VBox center = new VBox(2, centerValue, centerCaption);
    private final List<Arc> arcs = new ArrayList<>();
    private List<Slice> slices = List.of();
    private String idleValue = "";
    private String idleCaption = "";
    private Object highlighted;
    private Consumer<Object> onHover = key -> { };
    private Consumer<Object> onClick = key -> { };

    DonutChart() {
        getStyleClass().add("cleanup-donut");
        centerValue.getStyleClass().add("cleanup-donut-value");
        centerCaption.getStyleClass().add("cleanup-donut-caption");
        centerCaption.setWrapText(true);
        centerCaption.setAlignment(Pos.CENTER);
        centerCaption.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        center.setAlignment(Pos.CENTER);
        center.setMouseTransparent(true);
        getChildren().addAll(ring, center);
        setMinSize(180, 180);
        setPrefSize(250, 250);
    }

    void setSlices(List<Slice> values, String value, String caption) {
        this.slices = List.copyOf(values);
        this.idleValue = value;
        this.idleCaption = caption;
        arcs.clear();
        ring.getChildren().clear();
        for (Slice slice : slices) {
            Arc arc = new Arc();
            arc.setType(ArcType.OPEN);
            arc.setFill(Color.TRANSPARENT);
            arc.setStroke(slice.color());
            arc.setStrokeLineCap(StrokeLineCap.BUTT);
            arc.setCursor(Cursor.HAND);
            Tooltip tip = new Tooltip(slice.label() + "\n" + slice.text() + "  (" + percent(slice) + ")");
            tip.getStyleClass().add("cleanup-tooltip");
            tip.setShowDelay(Duration.millis(200));
            Tooltip.install(arc, tip);
            arc.setOnMouseEntered(event -> {
                highlight(slice.key());
                onHover.accept(slice.key());
            });
            arc.setOnMouseExited(event -> {
                highlight(null);
                onHover.accept(null);
            });
            arc.setOnMouseClicked(event -> onClick.accept(slice.key()));
            arcs.add(arc);
            ring.getChildren().add(arc);
        }
        showCenter(null);
        requestLayout();
    }

    void onHover(Consumer<Object> action) {
        this.onHover = action == null ? key -> { } : action;
    }

    void onClick(Consumer<Object> action) {
        this.onClick = action == null ? key -> { } : action;
    }

    /** Grows one slice and names it in the middle; null for none. */
    void highlight(Object key) {
        highlighted = key;
        Slice shown = null;
        for (Slice slice : slices) {
            if (slice.key().equals(key)) {
                shown = slice;
            }
        }
        showCenter(shown);
        requestLayout();
    }

    private void showCenter(Slice slice) {
        if (slice == null) {
            centerValue.setText(idleValue);
            centerCaption.setText(idleCaption);
            return;
        }
        centerValue.setText(slice.text());
        centerCaption.setText(slice.label() + "\n" + percent(slice));
    }

    private String percent(Slice slice) {
        double total = slices.stream().mapToDouble(Slice::value).sum();
        if (total <= 0) {
            return "0%";
        }
        double share = slice.value() * 100 / total;
        return share < 1 && share > 0 ? "<1%" : Math.round(share) + "%";
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        double size = Math.min(width, height);
        double cx = width / 2;
        double cy = height / 2;
        double thickness = Math.max(16, size * 0.12);
        double radius = size / 2 - thickness / 2 - 6;
        if (radius <= 0) {
            return;
        }
        double total = slices.stream().mapToDouble(Slice::value).sum();
        double gap = slices.size() > 1 ? Math.toDegrees(GAP / radius) : 0;
        double start = 90;
        for (int i = 0; i < arcs.size(); i++) {
            Arc arc = arcs.get(i);
            Slice slice = slices.get(i);
            double extent = total <= 0 ? 0 : 360 * slice.value() / total;
            double drawn = Math.max(extent * 0.5, extent - gap);
            arc.setCenterX(cx);
            arc.setCenterY(cy);
            arc.setRadiusX(radius);
            arc.setRadiusY(radius);
            arc.setStartAngle(start - (extent - drawn) / 2);
            arc.setLength(-drawn);
            boolean lifted = slice.key().equals(highlighted);
            arc.setStrokeWidth(lifted ? thickness + 8 : thickness);
            arc.setOpacity(highlighted == null || lifted ? 1 : 0.55);
            start -= extent;
        }
        double inner = Math.max(40, (radius - thickness / 2) * 2 * 0.86);
        center.setMaxWidth(inner);
        centerCaption.setMaxWidth(inner);
        center.resize(inner, inner);
        center.relocate(cx - inner / 2, cy - inner / 2);
    }
}
