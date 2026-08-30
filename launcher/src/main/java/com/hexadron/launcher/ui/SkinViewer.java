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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.AmbientLight;

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

    public SkinViewer() {
        getStyleClass().add("skin-viewer");

        // The figure hangs off two rotations that are never replaced, only
        // adjusted - so the spin, the drag and the buttons are all writing to
        // the same two numbers rather than composing transforms nobody can
        // untangle later.
        figure.getTransforms().addAll(pitch, spin);

        Group world = new Group(figure, new AmbientLight(Color.WHITE));

        SubScene scene = new SubScene(world, 10, 10, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        camera.setFieldOfView(38);
        scene.setCamera(camera);
        applyDistance();

        Pane holder = new Pane(scene);
        scene.widthProperty().bind(holder.widthProperty());
        scene.heightProperty().bind(holder.heightProperty());

        empty.getStyleClass().add("muted");
        empty.setText(I18n.t("account.preview.empty"));
        empty.setWrapText(true);
        empty.setMaxWidth(180);
        empty.setVisible(false);

        getChildren().addAll(holder, empty, buttons());
        setMinWidth(230);
        setPrefWidth(250);

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
