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

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.skin.SkinModel;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.AmbientLight;

import java.util.List;

/**
 * The player, turning slowly, in three dimensions.
 *
 * <h2>Why it is worth the code</h2>
 *
 * <p>A skin is a flat sheet of limbs laid out end to end; nobody can look at
 * one and know what it will look like on a player. The two small squares this
 * replaces showed the face and a scrap of cape, which answered "did the file
 * load" and nothing else. The question somebody actually has in front of a skin
 * picker is whether the back looks right, whether the arms match, whether the
 * cape sits where they expected - and every one of those is answered by turning
 * the figure round.
 *
 * <h2>How it behaves</h2>
 *
 * <ul>
 *   <li>It turns by itself, slowly, so the whole figure is seen without anybody
 *       having to do anything.</li>
 *   <li>The moment the pointer is over it, it stops - because somebody who has
 *       moved the mouse there is about to look at something in particular, and
 *       a model that keeps turning under the cursor has to be caught.</li>
 *   <li>Dragging turns it in both directions. The pitch stops short of straight
 *       up and straight down, where a model has nothing to show and it stops
 *       being obvious which way is which.</li>
 *   <li>The wheel moves the camera in and out, and the four buttons do the same
 *       without one - a trackpad without a wheel is common, and so is wanting a
 *       nudge rather than a spin.</li>
 * </ul>
 */
public final class SkinViewer extends StackPane {

    /** Degrees a second, unattended. Slow enough to read, quick enough to see. */
    private static final double IDLE_SPIN = 14;

    private static final double NEAREST = 45;
    private static final double FURTHEST = 190;

    /**
     * The panel's size, fixed.
     *
     * <p>It used to be stretched to whatever height the form beside it happened
     * to need, and that height changes with what is on the form - a sign-in row
     * appearing, a note wrapping to one more line. So choosing a different skin
     * service resized the figure, which is not a thing a skin service should do.
     */
    private static final double PANEL_WIDTH = 250;

    /**
     * Tall enough for the fullest state of the form beside it - a service
     * selected, signed in, on an offline account - so that the ordinary case
     * does not scroll. Anything longer than this, in any language, scrolls
     * rather than resizing the window.
     */
    private static final double PANEL_HEIGHT = 600;

    private final Group figure = new Group();
    private final Rotate spin = new Rotate(160, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(-8, Rotate.X_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);

    private final Label empty = new Label();

    private double distance = 105;
    private double lastX;
    private double lastY;
    private boolean hovered;

    private final AnimationTimer timer;

    /** Where a dropped file goes. Null until somebody wants them. */
    private java.util.function.Consumer<java.nio.file.Path> onDropped;

    public SkinViewer() {
        getStyleClass().add("skin-viewer");

        // The figure hangs off two rotations that are never replaced, only
        // adjusted - so the spin, the drag and the buttons are all writing to
        // the same two numbers rather than composing transforms nobody can
        // untangle later.
        figure.getTransforms().addAll(pitch, spin);

        Group world = new Group(figure, new AmbientLight(Color.WHITE));

        SubScene scene = new SubScene(world, PANEL_WIDTH, PANEL_HEIGHT, true,
                SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        camera.setFieldOfView(38);
        scene.setCamera(camera);
        applyDistance();

        // The size is written in layoutChildren, and the holder asks for no
        // space of its own.
        //
        // Binding the SubScene to the holder instead - which is what this was -
        // makes a loop: a Pane takes its preferred size from its children, a
        // SubScene's preferred size is its width and height, and those were
        // bound back to the Pane. Any size satisfies that, so the layout keeps
        // whatever it had rather than settling on the right one, and after the
        // panel changes size the SubScene is still rendering at the old one.
        // On screen that is a figure drawn small and pushed into a corner,
        // which is what somebody sees who has just clicked a radio button.
        Pane holder = new Pane(scene) {
            @Override
            protected void layoutChildren() {
                scene.setWidth(getWidth());
                scene.setHeight(getHeight());
            }

            @Override
            protected double computePrefWidth(double height) {
                return 0;
            }

            @Override
            protected double computePrefHeight(double width) {
                return 0;
            }

            @Override
            protected double computeMinWidth(double height) {
                return 0;
            }

            @Override
            protected double computeMinHeight(double width) {
                return 0;
            }
        };
        // Asks for nothing and accepts everything: the panel decides the size,
        // and this fills it. Explicit rather than relying on Region's default
        // maximum, because the whole point of the overrides above is that this
        // node's preferred size says nothing about how big it should be.
        holder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        empty.getStyleClass().add("muted");
        empty.setText(I18n.t("account.preview.empty"));
        empty.setWrapText(true);
        empty.setMaxWidth(180);
        empty.setVisible(false);

        getChildren().addAll(holder, empty, buttons());
        setMinSize(PANEL_WIDTH, PANEL_HEIGHT);
        setPrefSize(PANEL_WIDTH, PANEL_HEIGHT);
        setMaxSize(PANEL_WIDTH, PANEL_HEIGHT);

        setOnMouseEntered(event -> hovered = true);
        setOnMouseExited(event -> hovered = false);
        setOnMousePressed(event -> {
            lastX = event.getSceneX();
            lastY = event.getSceneY();
        });
        setOnMouseDragged(event -> {
            spin.setAngle(spin.getAngle() + (event.getSceneX() - lastX) * 0.5);
            pitch.setAngle(clampPitch(pitch.getAngle() - (event.getSceneY() - lastY) * 0.5));
            lastX = event.getSceneX();
            lastY = event.getSceneY();
        });
        setOnScroll(event -> zoom(event.getDeltaY() > 0 ? -8 : 8));

        setOnDragOver(this::dragOver);
        setOnDragDropped(this::dragDropped);
        setOnDragExited(event -> getStyleClass().remove("drag-over"));

        timer = new AnimationTimer() {
            private long previous;

            @Override
            public void handle(long now) {
                if (previous == 0) {
                    previous = now;
                    return;
                }
                double seconds = (now - previous) / 1_000_000_000.0;
                previous = now;
                if (!hovered) {
                    spin.setAngle(spin.getAngle() + IDLE_SPIN * seconds);
                }
            }
        };
        timer.start();
    }

    /**
     * Replaces the figure.
     *
     * <p>The camera is left where it is. Somebody who has turned the model to
     * look at the back and then picks a cape wants to see the back of the cape,
     * not to be swung round to the front again.
     */
    public void show(Image skin, Image cape, boolean slim) {
        figure.getChildren().setAll(SkinModel.build(skin, cape, slim));
        empty.setVisible(skin == null && cape == null);
    }

    /**
     * Takes files dropped on the figure.
     *
     * <p>Dropping a picture on a picture of the thing it is a picture of is the
     * shortest route there is between having a skin file and wearing it, and it
     * is the route somebody who has just downloaded one from a website will
     * reach for first.
     */
    public void onFileDropped(java.util.function.Consumer<java.nio.file.Path> handler) {
        this.onDropped = handler;
    }

    private void dragOver(DragEvent event) {
        if (onDropped != null && dropped(event) != null) {
            event.acceptTransferModes(TransferMode.COPY);
            if (!getStyleClass().contains("drag-over")) {
                getStyleClass().add("drag-over");
            }
        }
        event.consume();
    }

    private void dragDropped(DragEvent event) {
        getStyleClass().remove("drag-over");
        java.io.File file = dropped(event);
        if (file != null && onDropped != null) {
            onDropped.accept(file.toPath());
            event.setDropCompleted(true);
        }
        event.consume();
    }

    /**
     * The one PNG in a drop, or null.
     *
     * <p>Only the first is taken. A drop of six files is somebody's whole
     * downloads folder, and picking one of them at random to wear is worse than
     * asking them to drop the one they meant.
     */
    private static java.io.File dropped(DragEvent event) {
        if (!event.getDragboard().hasFiles()) {
            return null;
        }
        List<java.io.File> files = event.getDragboard().getFiles();
        if (files.size() != 1) {
            return null;
        }
        java.io.File file = files.get(0);
        return file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? file : null;
    }

    /** Stops the animation. Called when the window that owns this closes. */
    public void stop() {
        timer.stop();
    }

    private HBox buttons() {
        HBox row = new HBox(6,
                button("◀", "account.preview.left", () -> spin.setAngle(spin.getAngle() - 20)),
                button("▶", "account.preview.right", () -> spin.setAngle(spin.getAngle() + 20)),
                button("−", "account.preview.out", () -> zoom(14)),
                button("+", "account.preview.in", () -> zoom(-14)));
        row.setAlignment(Pos.BOTTOM_CENTER);
        row.setPadding(new Insets(0, 0, 10, 0));
        row.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(row, Pos.BOTTOM_CENTER);
        // Otherwise the row swallows drags aimed at the model behind it.
        row.setPickOnBounds(false);
        return row;
    }

    private Button button(String glyph, String tooltip, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().add("viewer-button");
        button.setFocusTraversable(false);
        Tooltip.install(button, new Tooltip(I18n.t(tooltip)));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void zoom(double by) {
        distance = Math.max(NEAREST, Math.min(FURTHEST, distance + by));
        applyDistance();
    }

    private void applyDistance() {
        camera.setTranslateZ(-distance);
    }

    private static double clampPitch(double angle) {
        return Math.max(-80, Math.min(80, angle));
    }
}
