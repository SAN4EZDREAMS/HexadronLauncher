/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.util.Locale;
import java.util.Optional;

/**
 * The launcher's own colour chooser.
 *
 * <h2>Why not the platform's</h2>
 *
 * <p>JavaFX ships one, behind {@code ColorPicker}, and it was used here first.
 * Two things it cannot do: it is drawn from {@code modena.css} rather than from
 * this launcher's stylesheet, so it opens as a white window in front of a dark
 * one; and its wording comes from JavaFX's own resource bundles, which do not
 * carry every language this launcher does - so a user reading the launcher in
 * Ukrainian was handed a dialog reading "Custom Colors", "Saturation",
 * "Opacity". Neither is reachable from outside JavaFX, because the window the
 * control builds is private to it.
 *
 * <p>So this one. It is a smaller thing than the platform's - no opacity, no
 * grid of named colours - and that is on purpose: a group colour is an opaque
 * {@code #rrggbb} and nothing else. What it does have is this launcher's own
 * surface and this launcher's own words.
 *
 * <h2>Hue, saturation, brightness - and the fields in RGB</h2>
 *
 * <p>The square and the bar work in HSB because that is the model a person picks
 * in: one gesture for "which colour", one for "how strong and how dark". The
 * numbers underneath are RGB and hex, because that is what a colour gets written
 * down as - and either can be typed into, which is the fast path for somebody
 * who already knows the value they want.
 */
final class ColorChooserDialog {

    private static final double FIELD_WIDTH = 240;
    private static final double FIELD_HEIGHT = 170;
    private static final double HUE_WIDTH = 20;

    /** The picked colour, as the square and the bar hold it. */
    private double hue;
    private double saturation = 1;
    private double brightness = 1;

    /**
     * True while the views are being written from {@link #hue} and friends.
     *
     * <p>Without it every refresh feeds the text fields, whose listeners parse
     * what they were just given and refresh again. The guard is what makes the
     * state the one source and the four views its readers, rather than five
     * things updating each other.
     */
    private boolean updating;

    private final Pane field = new Pane();
    private final Region fieldFill = new Region();
    private final Circle fieldThumb = new Circle(6.5);

    private final Pane hueBar = new Pane();
    private final Region hueFill = new Region();
    private final Region hueMark = new Region();

    private final Region before = new Region();
    private final Region after = new Region();

    private final TextField hexField = new TextField();
    private final TextField redField = new TextField();
    private final TextField greenField = new TextField();
    private final TextField blueField = new TextField();

    /**
     * Opens the chooser.
     *
     * @param initial the colour to start on, {@code #rrggbb}
     * @return the picked colour when Save was pressed, empty when cancelled
     */
    Optional<String> show(Window owner, String initial) {
        set(parse(initial));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("color.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType ok = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);
        dialog.getDialogPane().setContent(build(initial));
        Theme.apply(dialog.getDialogPane());

        if (dialog.showAndWait().filter(button -> button == ok).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hex());
    }

    private HBox build(String initial) {
        buildField();
        buildHueBar();

        before.getStyleClass().add("chooser-preview");
        before.setStyle("-fx-background-color: " + initial + ";");
        after.getStyleClass().add("chooser-preview");
        sized(before, 74, 34);
        sized(after, 74, 34);

        VBox beforeBox = new VBox(4, caption(I18n.t("color.current")), before);
        VBox afterBox = new VBox(4, caption(I18n.t("color.new")), after);
        HBox preview = new HBox(10, beforeBox, afterBox);

        hexField.setPrefColumnCount(7);
        hexField.setMaxWidth(104);
        hexField.textProperty().addListener((observable, previous, value) -> typedHex(value));
        HBox hexRow = new HBox(8, caption(I18n.t("color.hex")), hexField);
        hexRow.setAlignment(Pos.CENTER_LEFT);

        HBox rgbRow = new HBox(6, caption(I18n.t("color.rgb")),
                channel(redField), channel(greenField), channel(blueField));
        rgbRow.setAlignment(Pos.CENTER_LEFT);

        VBox side = new VBox(12, preview, hexRow, rgbRow);
        side.setAlignment(Pos.TOP_LEFT);

        HBox root = new HBox(14, field, hueBar, side);
        root.setPadding(new Insets(18, 18, 8, 18));
        root.setAlignment(Pos.TOP_LEFT);

        refresh();
        return root;
    }

    /**
     * The saturation-brightness square.
     *
     * <p>Three background layers rather than a drawn image: the hue underneath,
     * white fading out to the right, black fading in downwards. That is the same
     * square every colour picker draws, and as layers it costs nothing to
     * repaint when the hue changes - the only thing that changes is the bottom
     * one's colour.
     */
    private void buildField() {
        sized(fieldFill, FIELD_WIDTH, FIELD_HEIGHT);
        fieldFill.getStyleClass().add("chooser-field");

        fieldThumb.getStyleClass().add("chooser-thumb");
        fieldThumb.setMouseTransparent(true);

        field.getChildren().setAll(fieldFill, fieldThumb);
        sized(field, FIELD_WIDTH, FIELD_HEIGHT);
        field.setOnMousePressed(this::pickInField);
        field.setOnMouseDragged(this::pickInField);
    }

    private void pickInField(MouseEvent event) {
        saturation = clamp(event.getX() / FIELD_WIDTH);
        brightness = 1 - clamp(event.getY() / FIELD_HEIGHT);
        refresh();
    }

    private void buildHueBar() {
        sized(hueFill, HUE_WIDTH, FIELD_HEIGHT);
        hueFill.getStyleClass().add("chooser-hue");

        sized(hueMark, HUE_WIDTH, 3);
        hueMark.getStyleClass().add("chooser-hue-mark");
        hueMark.setMouseTransparent(true);

        hueBar.getChildren().setAll(hueFill, hueMark);
        sized(hueBar, HUE_WIDTH, FIELD_HEIGHT);
        hueBar.setOnMousePressed(this::pickHue);
        hueBar.setOnMouseDragged(this::pickHue);
    }

    private void pickHue(MouseEvent event) {
        hue = clamp(event.getY() / FIELD_HEIGHT) * 360;
        refresh();
    }

    /** Writes the state into every view at once. */
    private void refresh() {
        updating = true;
        try {
            // Written inline rather than through a looked-up colour in the
            // stylesheet: the three layers are one property, and splitting the
            // hue out of them means a rule that only works if the inline value
            // resolves ahead of the class - which is a subtlety to depend on for
            // a square that is either right or blank.
            fieldFill.setStyle("-fx-background-color: " + web(Color.hsb(hue, 1, 1))
                    + ", linear-gradient(to right, white, transparent)"
                    + ", linear-gradient(to bottom, transparent, black);");

            fieldThumb.setCenterX(saturation * FIELD_WIDTH);
            fieldThumb.setCenterY((1 - brightness) * FIELD_HEIGHT);
            hueMark.setLayoutY(hue / 360 * FIELD_HEIGHT - 1);

            Color picked = colour();
            after.setStyle("-fx-background-color: " + hex() + ";");
            hexField.setText(hex());
            redField.setText(String.valueOf(channelOf(picked.getRed())));
            greenField.setText(String.valueOf(channelOf(picked.getGreen())));
            blueField.setText(String.valueOf(channelOf(picked.getBlue())));
        } finally {
            updating = false;
        }
    }

    /**
     * A typed hex value.
     *
     * <p>Anything that is not a complete {@code #rrggbb} is left alone rather
     * than corrected: the user is halfway through typing one, and a field that
     * rewrites what is in it after every keystroke cannot be typed into.
     */
    private void typedHex(String value) {
        if (updating || value == null || !value.trim().matches("#?[0-9a-fA-F]{6}")) {
            return;
        }
        String text = value.trim();
        set(Color.web(text.startsWith("#") ? text : "#" + text));
        refresh();
    }

    private TextField channel(TextField input) {
        input.setPrefColumnCount(3);
        input.setMaxWidth(58);
        input.textProperty().addListener((observable, previous, value) -> {
            if (updating || value == null || !value.trim().matches("\\d{1,3}")
                    || Integer.parseInt(value.trim()) > 255) {
                return;
            }
            set(Color.rgb(number(redField), number(greenField), number(blueField)));
            refresh();
        });
        return input;
    }

    private static int number(TextField input) {
        String text = input.getText() == null ? "" : input.getText().trim();
        if (!text.matches("\\d{1,3}")) {
            return 0;
        }
        return Math.min(255, Integer.parseInt(text));
    }

    /**
     * Takes a colour apart into the state.
     *
     * <p>Black and white have no hue to read back, so the hue already on the bar
     * is kept. Otherwise picking black would swing the bar to red, and the next
     * drag out of the corner would come back a colour nobody chose.
     */
    private void set(Color colour) {
        if (colour.getSaturation() > 0) {
            hue = colour.getHue();
        }
        saturation = colour.getSaturation();
        brightness = colour.getBrightness();
    }

    private Color colour() {
        return Color.hsb(hue, saturation, brightness);
    }

    /** The picked colour as {@code #rrggbb}. Opacity is never part of it. */
    String hex() {
        return web(colour());
    }

    private static String web(Color colour) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                channelOf(colour.getRed()),
                channelOf(colour.getGreen()),
                channelOf(colour.getBlue()));
    }

    private static int channelOf(double component) {
        return (int) Math.round(component * 255);
    }

    private static Color parse(String value) {
        if (value != null && value.matches("#[0-9a-fA-F]{6}")) {
            return Color.web(value);
        }
        return Color.hsb(0, 1, 1);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static void sized(Region region, double width, double height) {
        region.setMinSize(width, height);
        region.setPrefSize(width, height);
        region.setMaxSize(width, height);
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }
}
