package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one place a group is set up, for both creating and editing.
 *
 * <h2>Why a colour picker at all</h2>
 *
 * <p>In the grid a group has no heading - a cell has a fixed place and there is
 * no room for one - so its colour is the only thing that says which band is
 * which. That makes the colour a property of the group in the same way its name
 * is, and a property somebody has to live with is a property they should be able
 * to choose. It was assigned and unchangeable, so two groups whose colours read
 * alike on a particular screen stayed that way.
 *
 * <p>A fixed palette rather than a full colour chooser. Every swatch here is
 * legible as a band tint behind the cells and as a plate with white text on it,
 * which an arbitrary colour is not - a group the user painted pale yellow would
 * have an unreadable name and a band indistinguishable from the selection.
 *
 * <h2>Save writes, Cancel writes nothing</h2>
 *
 * <p>The same rule as the instance editor. The dialog hands back what was chosen
 * and touches no state itself, so a cancelled edit cannot have half-applied a
 * rename.
 */
public final class GroupDialog {

    /** Size of a swatch, in pixels. */
    private static final double SWATCH = 26;

    /** What the dialog came back with. */
    public record Choice(String name, String color) {
    }

    private final TextField nameField = new TextField();
    private final HBox swatches = new HBox(6);
    private String chosen;

    /**
     * Opens the dialog.
     *
     * @param name  the name to start from, or null when creating
     * @param color the colour to start from; when it is not one of the palette's
     *              it is offered as an extra swatch, so an arrangement that came
     *              from an older file does not silently lose its colour
     * @return the name and colour when Save was pressed, empty when cancelled
     */
    public Optional<Choice> show(Window owner, String name, String color) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("groups.settings.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType save = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        dialog.getDialogPane().setContent(buildForm(name, color));
        dialog.getDialogPane().setPrefWidth(520);
        Theme.apply(dialog.getDialogPane());

        // The name is what somebody came here to type, in the usual case.
        javafx.application.Platform.runLater(() -> {
            nameField.requestFocus();
            nameField.selectAll();
        });

        if (dialog.showAndWait().filter(button -> button == save).isEmpty()) {
            return Optional.empty();
        }
        String typed = nameField.getText() == null ? "" : nameField.getText().trim();
        if (typed.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Choice(typed, chosen));
    }

    private GridPane buildForm(String name, String color) {
        List<String> offered = new ArrayList<>(ProfileLayout.palette());
        if (color != null && !offered.contains(color)) {
            offered.add(0, color);
        }
        chosen = color != null ? color : offered.get(0);

        nameField.setText(name == null ? I18n.t("groups.new.default") : name);
        nameField.setPromptText(I18n.t("groups.name.prompt"));

        for (String candidate : offered) {
            swatches.getChildren().add(swatch(candidate));
        }
        swatches.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(I18n.t("groups.color.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        note.setMaxWidth(340);
        note.setMinHeight(Region.USE_PREF_SIZE);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 18, 8, 18));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        grid.addRow(0, formLabel(I18n.t("groups.name")), nameField);
        grid.addRow(1, formLabel(I18n.t("groups.color")), swatches);
        grid.addRow(2, new Label(), note);
        return grid;
    }

    /**
     * One colour.
     *
     * <p>The chosen one is marked with a style class rather than a border colour
     * written into the same inline style as the fill: the fill has to be inline
     * because it is data, and an inline border would then be unable to change on
     * selection without rewriting the fill with it.
     */
    private Region swatch(String color) {
        Region region = new Region();
        region.getStyleClass().add("swatch");
        region.setStyle("-fx-background-color: " + color + ";");
        region.setMinSize(SWATCH, SWATCH);
        region.setPrefSize(SWATCH, SWATCH);
        region.setMaxSize(SWATCH, SWATCH);
        Tooltip.install(region, new Tooltip(color));
        if (color.equalsIgnoreCase(chosen)) {
            region.getStyleClass().add("swatch-chosen");
        }
        region.setOnMouseClicked(event -> {
            chosen = color;
            swatches.getChildren().forEach(node ->
                    node.getStyleClass().remove("swatch-chosen"));
            region.getStyleClass().add("swatch-chosen");
            event.consume();
        });
        return region;
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }
}
