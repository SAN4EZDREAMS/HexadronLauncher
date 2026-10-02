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
import com.hexadron.launcher.theme.Appearance;
import com.hexadron.launcher.theme.BackdropImage;
import com.hexadron.launcher.theme.Palette;
import com.hexadron.launcher.theme.ThemeFile;
import com.hexadron.launcher.theme.ThemePreset;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The Appearance tab of the settings window: theme, colours, background
 * picture and fonts.
 *
 * <h2>Seen at once, kept on Save</h2>
 *
 * <p>Every change is put on screen as it is made - in this window and in every
 * other one - because a colour is judged by looking at it, and judging it means
 * seeing it on the real panels rather than on a sample. Nothing is written to
 * the settings until Save; Cancel puts back the look the window opened with.
 *
 * <p>Sliders preview after a short pause rather than on every step: each look
 * is a stylesheet written to disk and, with a picture, a picture prepared, and
 * a drag of the blur slider would otherwise queue forty of them.
 */
final class AppearanceTab {

    /** How long a slider has to rest before its value is previewed. */
    private static final Duration SETTLE = Duration.millis(140);

    private final Appearance initial;
    private final Path dataRoot;
    private final Supplier<Window> owner;
    private Appearance current;

    private final ToggleGroup presets = new ToggleGroup();
    private final EnumMap<ThemePreset, ToggleButton> presetCards = new EnumMap<>(ThemePreset.class);
    private final EnumMap<Palette.Slot, Region> swatches = new EnumMap<>(Palette.Slot.class);
    private final EnumMap<Palette.Slot, Label> hexLabels = new EnumMap<>(Palette.Slot.class);
    private final Label changedNote = SettingsDialog.note("appearance.theme.note");
    private final Hyperlink resetColors = new Hyperlink();
    private final Label contrastWarning = new Label();
    private final CheckBox patternBox = new CheckBox();
    private final Label patternNote = new Label();

    private final Label pictureName = new Label();
    private final Button removePicture = new Button();
    private final ComboBox<Appearance.Fit> fitBox = new ComboBox<>();
    private final Slider dimSlider = new Slider(0, Appearance.DIM_MAX, 0);
    private final Slider blurSlider = new Slider(0, Appearance.BLUR_MAX, 0);
    private final Slider panelSlider = new Slider(Appearance.PANEL_OPACITY_MIN, 100, 100);
    private final Label dimValue = new Label();
    private final Label blurValue = new Label();
    private final Label panelValue = new Label();
    private final Label pictureResult = new Label();

    private final ComboBox<String> fontBox = new ComboBox<>();
    private final ComboBox<String> monoBox = new ComboBox<>();
    private final Slider scaleSlider = new Slider(Appearance.FONT_SCALE_MIN, Appearance.FONT_SCALE_MAX, 100);
    private final Label scaleValue = new Label();
    private final Label fileResult = new Label();

    private final PauseTransition settle = new PauseTransition(SETTLE);

    /** True while the controls are being set from {@link #current}, so they do not write it back. */
    private boolean filling;

    AppearanceTab(Appearance initial, Path dataRoot, Supplier<Window> owner) {
        this.initial = initial == null ? Appearance.DEFAULT : initial;
        this.current = this.initial;
        this.dataRoot = dataRoot;
        this.owner = owner;
        settle.setOnFinished(event -> Theme.use(current));
    }

    /** What is on screen now. */
    Appearance value() {
        return current;
    }

    /** Puts on screen a change a slider is still settling on, for Save. */
    void commit() {
        settle.stop();
        if (!Theme.appearance().equals(current)) {
            Theme.use(current);
        }
    }

    /** Puts back the look the window opened with. */
    void revert() {
        settle.stop();
        if (!Theme.appearance().equals(initial)) {
            Theme.use(initial);
        }
    }

    // ---------------------------------------------------------------- building

    GridPane build() {
        GridPane grid = SettingsDialog.form();
        int row = 0;

        grid.addRow(row++, top(SettingsDialog.label("appearance.theme")), presetGallery());
        resetColors.setText(I18n.t("appearance.colors.reset"));
        resetColors.getStyleClass().add("about-link");
        resetColors.setOnAction(event -> change(current.withoutColorChanges(), true));
        HBox changedLine = new HBox(10, changedNote, resetColors);
        changedLine.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(changedNote, Priority.ALWAYS);
        grid.addRow(row++, new Label(), changedLine);

        patternBox.setText(I18n.t("appearance.pattern"));
        patternBox.selectedProperty().addListener((observable, previous, on) -> {
            if (!filling) {
                change(current.withPattern(on), true);
            }
        });
        patternNote.getStyleClass().add("muted");
        patternNote.setWrapText(true);
        patternNote.setMaxWidth(Double.MAX_VALUE);
        patternNote.setMinHeight(Region.USE_PREF_SIZE);
        VBox patternLine = new VBox(4, patternBox, patternNote);
        grid.addRow(row++, new Label(), patternLine);

        grid.addRow(row++, top(SettingsDialog.label("appearance.colors")), colorGrid());
        contrastWarning.getStyleClass().addAll("muted", "dialog-warning");
        contrastWarning.setWrapText(true);
        contrastWarning.setMaxWidth(Double.MAX_VALUE);
        contrastWarning.setMinHeight(Region.USE_PREF_SIZE);
        grid.addRow(row++, new Label(), contrastWarning);
        grid.addRow(row++, new Label(), new Separator());

        Button choose = new Button(I18n.t("appearance.background.choose"));
        choose.setOnAction(event -> choosePicture());
        removePicture.setText(I18n.t("appearance.background.remove"));
        removePicture.setOnAction(event -> change(current.withBackground(""), true));
        pictureName.getStyleClass().add("summary-value");
        pictureName.setMinWidth(0);
        HBox pictureLine = new HBox(8, pictureName, spacer(), choose, removePicture);
        pictureLine.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(row++, SettingsDialog.label("appearance.background"), pictureLine);

        fitBox.setItems(FXCollections.observableArrayList(Appearance.Fit.values()));
        fitBox.setMaxWidth(Double.MAX_VALUE);
        fitBox.setConverter(converter(fit -> fit == null ? "" : I18n.t(fit.labelKey())));
        fitBox.valueProperty().addListener((observable, previous, fit) -> {
            if (!filling && fit != null) {
                change(current.withFit(fit), true);
            }
        });
        grid.addRow(row++, SettingsDialog.label("appearance.background.fit"), fitBox);
        grid.addRow(row++, SettingsDialog.label("appearance.background.dim"),
                sliderLine(dimSlider, dimValue, value -> current.withDim(value), "appearance.value.percent", 5));
        grid.addRow(row++, SettingsDialog.label("appearance.background.blur"),
                sliderLine(blurSlider, blurValue, value -> current.withBlur(value), "appearance.value.pixels", 1));
        grid.addRow(row++, SettingsDialog.label("appearance.background.panels"),
                sliderLine(panelSlider, panelValue, value -> current.withPanelOpacity(value),
                        "appearance.value.percent", 5));
        pictureResult.getStyleClass().addAll("muted", "dialog-warning");
        pictureResult.setWrapText(true);
        pictureResult.setMaxWidth(Double.MAX_VALUE);
        pictureResult.setMinHeight(Region.USE_PREF_SIZE);
        grid.addRow(row++, new Label(), pictureResult);
        grid.addRow(row++, new Label(), SettingsDialog.note("appearance.background.note"));
        grid.addRow(row++, new Label(), new Separator());

        List<String> families = new ArrayList<>(Font.getFamilies());
        families.sort(String.CASE_INSENSITIVE_ORDER);
        fontBox.setItems(FXCollections.observableArrayList(withDefault(families)));
        monoBox.setItems(FXCollections.observableArrayList(withDefault(monospaced(families))));
        for (ComboBox<String> box : List.of(fontBox, monoBox)) {
            box.setMaxWidth(Double.MAX_VALUE);
            box.setVisibleRowCount(14);
            box.setConverter(converter(name -> name == null || name.isEmpty()
                    ? I18n.t("appearance.font.default") : name));
            box.setCellFactory(list -> new FontCell());
        }
        fontBox.valueProperty().addListener((observable, previous, name) -> {
            if (!filling && name != null) {
                change(current.withFont(name), true);
            }
        });
        monoBox.valueProperty().addListener((observable, previous, name) -> {
            if (!filling && name != null) {
                change(current.withMonoFont(name), true);
            }
        });
        grid.addRow(row++, SettingsDialog.label("appearance.font"), fontBox);
        grid.addRow(row++, SettingsDialog.label("appearance.font.mono"), monoBox);
        grid.addRow(row++, SettingsDialog.label("appearance.font.scale"),
                sliderLine(scaleSlider, scaleValue, value -> current.withFontScale(value),
                        "appearance.value.percent", Appearance.FONT_SCALE_STEP));
        grid.addRow(row++, new Label(), SettingsDialog.note("appearance.font.note"));
        grid.addRow(row++, new Label(), new Separator());

        Button export = new Button(I18n.t("appearance.export"));
        export.setOnAction(event -> exportTheme());
        Button importTheme = new Button(I18n.t("appearance.import"));
        importTheme.setOnAction(event -> importTheme());
        Button reset = new Button(I18n.t("appearance.reset"));
        reset.setOnAction(event -> change(Appearance.DEFAULT, true));
        HBox files = new HBox(8, export, importTheme, spacer(), reset);
        files.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(row++, new Label(), files);
        fileResult.getStyleClass().add("muted");
        fileResult.setWrapText(true);
        fileResult.setMaxWidth(Double.MAX_VALUE);
        fileResult.setMinHeight(Region.USE_PREF_SIZE);
        grid.addRow(row++, new Label(), fileResult);
        grid.addRow(row, new Label(), SettingsDialog.note("appearance.preview.note"));

        fill();
        return grid;
    }

    /** One card per theme, each a small drawing of a window in its colours. */
    private FlowPane presetGallery() {
        FlowPane gallery = new FlowPane(10, 10);
        for (ThemePreset preset : ThemePreset.values()) {
            Palette palette = preset.palette();
            StackPane picture = new StackPane();
            picture.getStyleClass().add("theme-card-picture");
            picture.setMinSize(112, 60);
            picture.setPrefSize(112, 60);
            picture.setMaxSize(112, 60);
            picture.setStyle(fill(palette.get(Palette.Slot.BACKGROUND)) + " -fx-background-radius: 6;"
                    + patternStyle(preset));

            Region side = block(28, 48, palette.get(Palette.Slot.PANEL));
            Region line1 = block(46, 5, palette.get(Palette.Slot.TEXT));
            Region line2 = block(32, 4, palette.get(Palette.Slot.TEXT_MUTED));
            Region button = block(30, 10, palette.get(Palette.Slot.ACCENT));
            VBox body = new VBox(5, line1, line2, button);
            body.setAlignment(Pos.TOP_LEFT);
            HBox window = new HBox(8, side, body);
            window.setPadding(new Insets(6));
            window.setAlignment(Pos.TOP_LEFT);
            picture.getChildren().add(window);

            Label name = new Label(I18n.t(preset.labelKey()));
            VBox content = new VBox(6, picture, name);
            content.setAlignment(Pos.CENTER);

            ToggleButton card = new ToggleButton();
            card.getStyleClass().add("theme-card");
            card.setGraphic(content);
            card.setToggleGroup(presets);
            card.setUserData(preset);
            card.setOnAction(event -> {
                if (filling) {
                    return;
                }
                if (current.preset() == preset) {
                    // A theme card is not a switch that turns off.
                    card.setSelected(true);
                    return;
                }
                change(current.withPreset(preset), true);
            });
            presetCards.put(preset, card);
            gallery.getChildren().add(card);
        }
        return gallery;
    }

    /**
     * The theme's tile behind its card, small, so a card shows what the
     * window will; nothing for a theme without one.
     */
    private static String patternStyle(ThemePreset preset) {
        String name = preset.patternResource();
        var url = name == null ? null : AppearanceTab.class.getResource(name);
        if (url == null) {
            return "";
        }
        return " -fx-background-image: url(\"" + url.toExternalForm().replace("\"", "%22") + "\");"
                + " -fx-background-size: 128 128; -fx-background-repeat: repeat;"
                + " -fx-background-position: left top;";
    }

    /** The twelve colours, each a swatch that opens the colour chooser. */
    private GridPane colorGrid() {
        GridPane colours = new GridPane();
        colours.setHgap(10);
        colours.setVgap(6);
        int index = 0;
        for (Palette.Slot slot : Palette.Slot.values()) {
            Region swatch = new Region();
            swatch.getStyleClass().add("swatch");
            swatch.setMinSize(22, 22);
            swatch.setPrefSize(22, 22);
            swatch.setMaxSize(22, 22);
            Label name = new Label(I18n.t(slot.labelKey()));
            Label hex = new Label();
            hex.getStyleClass().add("muted");
            HBox cell = new HBox(8, swatch, name, hex);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("appearance-color");
            cell.setPadding(new Insets(3, 6, 3, 3));
            cell.setOnMouseClicked(event -> chooseColor(slot));
            cell.setCursor(javafx.scene.Cursor.HAND);
            Tooltip.install(cell, new Tooltip(I18n.t("appearance.color.choose", I18n.t(slot.labelKey()))));
            swatches.put(slot, swatch);
            hexLabels.put(slot, hex);
            colours.add(cell, index % 2, index / 2);
            index++;
        }
        return colours;
    }

    private HBox sliderLine(Slider slider, Label value, IntFunction<Appearance> apply, String format, int step) {
        slider.setMajorTickUnit(step);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(step);
        HBox.setHgrow(slider, Priority.ALWAYS);
        slider.setMaxWidth(Double.MAX_VALUE);
        value.setMinWidth(54);
        value.getStyleClass().add("summary-value");
        slider.valueProperty().addListener((observable, previous, number) -> {
            int snapped = (int) Math.round(number.doubleValue() / step) * step;
            value.setText(I18n.t(format, String.valueOf(snapped)));
            if (!filling) {
                change(apply.apply(snapped), false);
            }
        });
        HBox line = new HBox(10, slider, value);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    // ---------------------------------------------------------------- changing

    /**
     * Takes a new look: the controls are brought into line with it and it is
     * put on screen - now, or once a slider has come to rest.
     */
    private void change(Appearance next, boolean now) {
        if (next.equals(current)) {
            return;
        }
        current = next;
        fill();
        if (now) {
            settle.stop();
            Theme.use(current);
        } else {
            settle.playFromStart();
        }
    }

    /** Every control from {@link #current}. */
    private void fill() {
        filling = true;
        try {
            ToggleButton card = presetCards.get(current.preset());
            if (card != null) {
                card.setSelected(true);
            }
            Palette palette = current.palette();
            for (Palette.Slot slot : Palette.Slot.values()) {
                String hex = palette.get(slot);
                swatches.get(slot).setStyle(fill(hex) + " -fx-background-radius: 5; -fx-border-radius: 5;");
                hexLabels.get(slot).setText(current.overrides().containsKey(slot) ? hex + "  *" : hex);
            }
            int changed = current.overrides().size();
            changedNote.setText(changed == 0 ? I18n.t("appearance.theme.note")
                    : I18n.t("appearance.changed", String.valueOf(changed)));
            resetColors.setVisible(changed > 0);
            resetColors.setManaged(changed > 0);

            double contrast = Palette.contrast(palette.get(Palette.Slot.TEXT), palette.get(Palette.Slot.PANEL));
            boolean poor = contrast < 4.5;
            contrastWarning.setText(poor ? I18n.t("appearance.contrast",
                    String.format(Locale.ROOT, "%.1f", contrast)) : "");
            contrastWarning.setVisible(poor);
            contrastWarning.setManaged(poor);

            boolean picture = current.hasBackground();
            pictureName.setText(picture ? current.background().substring(current.background().indexOf('/') + 1)
                    : I18n.t("appearance.background.none"));
            removePicture.setDisable(!picture);
            fitBox.setValue(current.fit());
            dimSlider.setValue(current.dim());
            blurSlider.setValue(current.blur());
            panelSlider.setValue(current.panelOpacity());
            boolean pattern = current.showsPattern();
            for (Node control : List.of(fitBox, blurSlider)) {
                control.setDisable(!picture);
            }
            // A theme's pattern is faded and shows through the panels as a
            // picture does; it is not stretched or blurred.
            for (Node control : List.of(dimSlider, panelSlider)) {
                control.setDisable(!picture && !pattern);
            }
            boolean hasPattern = current.preset().hasPattern();
            patternBox.setSelected(current.pattern());
            patternBox.setDisable(!hasPattern || picture);
            patternNote.setText(!hasPattern ? I18n.t("appearance.pattern.none")
                    : picture ? I18n.t("appearance.pattern.replaced") : I18n.t("appearance.pattern.note"));
            dimValue.setText(I18n.t("appearance.value.percent", String.valueOf(current.dim())));
            blurValue.setText(I18n.t("appearance.value.pixels", String.valueOf(current.blur())));
            panelValue.setText(I18n.t("appearance.value.percent", String.valueOf(current.panelOpacity())));
            if (pictureResult.getText() == null || pictureResult.getText().isEmpty()) {
                pictureResult.setVisible(false);
                pictureResult.setManaged(false);
            }

            fontBox.setValue(current.font());
            monoBox.setValue(current.monoFont());
            scaleSlider.setValue(current.fontScale());
            scaleValue.setText(I18n.t("appearance.value.percent", String.valueOf(current.fontScale())));
        } finally {
            filling = false;
        }
    }

    private void chooseColor(Palette.Slot slot) {
        new ColorChooserDialog().show(owner.get(), current.palette().get(slot))
                .ifPresent(hex -> change(current.withColor(slot, hex), true));
    }

    private void choosePicture() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("appearance.background"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("appearance.background.filter"), "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(owner.get());
        if (file == null) {
            return;
        }
        try {
            String relative = BackdropImage.importPicture(file.toPath(), dataRoot);
            showPictureResult("");
            change(current.withBackground(relative), true);
        } catch (IOException | RuntimeException e) {
            showPictureResult(I18n.t("appearance.background.failed",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void showPictureResult(String text) {
        pictureResult.setText(text);
        pictureResult.setVisible(!text.isEmpty());
        pictureResult.setManaged(!text.isEmpty());
    }

    private void exportTheme() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("appearance.export"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("appearance.file.filter"), "*." + ThemeFile.EXTENSION));
        chooser.setInitialFileName(I18n.t(current.preset().labelKey()) + "." + ThemeFile.EXTENSION);
        File file = chooser.showSaveDialog(owner.get());
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + ThemeFile.EXTENSION)) {
            target = target.resolveSibling(target.getFileName() + "." + ThemeFile.EXTENSION);
        }
        try {
            ThemeFile.write(current, dataRoot, target);
            fileResult.setText(I18n.t("appearance.file.saved", target.getFileName().toString()));
        } catch (IOException | RuntimeException e) {
            fileResult.setText(I18n.t("appearance.file.failed",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void importTheme() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("appearance.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("appearance.file.filter"), "*." + ThemeFile.EXTENSION));
        File file = chooser.showOpenDialog(owner.get());
        if (file == null) {
            return;
        }
        try {
            Appearance read = ThemeFile.read(file.toPath(), dataRoot);
            fileResult.setText("");
            change(read, true);
        } catch (IOException | RuntimeException e) {
            fileResult.setText(I18n.t("appearance.file.failed",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A list cell that shows a font family in that family. */
    private static final class FontCell extends ListCell<String> {
        @Override
        protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setText(null);
                setStyle("");
                return;
            }
            if (name.isEmpty()) {
                setText(I18n.t("appearance.font.default"));
                setStyle("");
            } else {
                setText(name);
                setStyle("-fx-font-family: \"" + Appearance.fontName(name) + "\";");
            }
        }
    }

    private static List<String> withDefault(List<String> families) {
        List<String> list = new ArrayList<>(families.size() + 1);
        list.add("");
        list.addAll(families);
        return list;
    }

    /**
     * The families whose narrow and wide letters are the same width. Measured,
     * because a font does not say whether it is fixed-width in any way JavaFX
     * passes on.
     */
    static List<String> monospaced(List<String> families) {
        List<String> mono = new ArrayList<>();
        Text narrow = new Text("iiiiiiiiii");
        Text wide = new Text("WWWWWWWWWW");
        for (String family : families) {
            Font font = Font.font(family, 12);
            narrow.setFont(font);
            wide.setFont(font);
            double a = narrow.getLayoutBounds().getWidth();
            double b = wide.getLayoutBounds().getWidth();
            if (a > 0 && Math.abs(a - b) < 0.5) {
                mono.add(family);
            }
        }
        return mono;
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return text.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    private static Region block(double width, double height, String colour) {
        Region region = new Region();
        region.setMinSize(width, height);
        region.setPrefSize(width, height);
        region.setMaxSize(width, height);
        region.setStyle(fill(colour) + " -fx-background-radius: 2;");
        return region;
    }

    private static String fill(String colour) {
        return "-fx-background-color: " + colour + ";";
    }

    /** A row label at the top of a tall row rather than in its middle. */
    private static Label top(Label label) {
        GridPane.setValignment(label, javafx.geometry.VPos.TOP);
        label.setPadding(new Insets(6, 0, 0, 0));
        return label;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
