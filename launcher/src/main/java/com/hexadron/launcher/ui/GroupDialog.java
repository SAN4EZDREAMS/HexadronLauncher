package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.LauncherSettings;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <h2>A palette, and then anything</h2>
 *
 * <p>The fixed palette is the fast path: sixteen colours that are known to work
 * as a band behind the cells and as a plate with white text on it, so the usual
 * case is one click. Beside it is a full picker, because a fixed palette is a
 * guess about what somebody's arrangement means, and a launcher has no business
 * telling a user that their eight servers may not each have the colour they
 * think of them in.
 *
 * <p>What is mixed there is kept - in {@link LauncherSettings}, not on the group
 * - and offered on every later group as an extra swatch. A colour that has to be
 * mixed again each time it is wanted is a colour that gets used once.
 *
 * <h2>Save writes, Cancel writes nothing</h2>
 *
 * <p>The same rule as the instance editor. The dialog hands back what was chosen
 * and touches no group state itself, so a cancelled edit cannot have
 * half-applied a rename.
 *
 * <p>The shelf of mixed colours is the one exception, and deliberately: it is
 * not part of the group being edited. Mixing a colour, deciding it is wrong for
 * this group and pressing Cancel should still leave the colour on the shelf,
 * because the work of mixing it was done either way.
 */
public final class GroupDialog {

    /** Size of a swatch, in pixels. */
    private static final double SWATCH = 26;

    /** What the dialog came back with. */
    public record Choice(String name, String color) {
    }

    /** Where mixed colours are kept between dialogs. Null means they are not. */
    private final LauncherSettings settings;

    /** Writes {@link #settings} to disk. Null means the caller does not care. */
    private final Runnable persist;

    private final TextField nameField = new TextField();
    private final FlowPane swatches = new FlowPane(6, 6);
    private final ColorPicker picker = new ColorPicker();

    private String chosen;

    /**
     * The group's own colour when it is neither in the palette nor on the shelf.
     *
     * <p>Kept as a swatch of its own so that an arrangement written by an older
     * version, or edited by hand, does not lose its colour the moment somebody
     * opens this dialog to change the name.
     */
    private String inherited;

    /** A dialog with no memory: the palette only, nothing kept between openings. */
    public GroupDialog() {
        this(null, null);
    }

    /**
     * @param settings where mixed colours are remembered, or null for none
     * @param persist  called after the shelf changes, to write the settings out
     */
    public GroupDialog(LauncherSettings settings, Runnable persist) {
        this.settings = settings;
        this.persist = persist;
    }

    /**
     * Opens the dialog.
     *
     * @param name  the name to start from, or null when creating
     * @param color the colour to start from; when it is neither in the palette
     *              nor on the shelf it is offered as an extra swatch
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
        chosen = color != null ? color.toLowerCase(Locale.ROOT)
                : ProfileLayout.palette().get(0);
        inherited = offered().contains(chosen) ? null : chosen;

        nameField.setText(name == null ? I18n.t("groups.new.default") : name);
        nameField.setPromptText(I18n.t("groups.name.prompt"));

        swatches.setAlignment(Pos.CENTER_LEFT);
        // Wraps rather than scrolls: every colour has to be visible at once for
        // the row to be a palette instead of a list to be paged through.
        swatches.setPrefWrapLength(SWATCH * 11 + 6 * 10);
        rebuildSwatches();

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
        return grid;
    }

    /** Every colour on offer, in the order it is shown, without the mixer. */
    private List<String> offered() {
        List<String> all = new ArrayList<>(ProfileLayout.palette());
        all.addAll(customColors());
        return all;
    }

    private List<String> customColors() {
        return settings == null ? List.of() : settings.customGroupColors();
    }

    /**
     * Redraws the row.
     *
     * <p>Wholesale rather than by touching the one swatch that changed: the row
     * has under twenty children, and every way of doing it in place has to keep
     * the selection ring, the shelf and the order in step by hand.
     */
    private void rebuildSwatches() {
        swatches.getChildren().clear();
        if (inherited != null) {
            swatches.getChildren().add(swatch(inherited, false));
        }
        for (String candidate : ProfileLayout.palette()) {
            swatches.getChildren().add(swatch(candidate, false));
        }
        for (String candidate : customColors()) {
            swatches.getChildren().add(swatch(candidate, true));
        }
        swatches.getChildren().add(mixer());
    }

    /**
     * One colour.
     *
     * <p>The chosen one is marked with a style class rather than a border colour
     * written into the same inline style as the fill: the fill has to be inline
     * because it is data, and an inline border would then be unable to change on
     * selection without rewriting the fill with it.
     *
     * @param mixed whether this one came off the shelf, and so can be forgotten
     */
    private Region swatch(String color, boolean mixed) {
        Region region = new Region();
        region.getStyleClass().add("swatch");
        region.setStyle("-fx-background-color: " + color + ";");
        region.setMinSize(SWATCH, SWATCH);
        region.setPrefSize(SWATCH, SWATCH);
        region.setMaxSize(SWATCH, SWATCH);
        Tooltip.install(region, new Tooltip(mixed
                ? color + "  ·  " + I18n.t("groups.color.mixed") : color));
        if (color.equalsIgnoreCase(chosen)) {
            region.getStyleClass().add("swatch-chosen");
        }
        region.setOnMouseClicked(event -> {
            chosen = color;
            rebuildSwatches();
            event.consume();
        });
        if (mixed) {
            // Only mixed colours can be taken off: the palette is the thing that
            // is always there, and a user who removed half of it would have a
            // dialog that no longer matches the one in the next screenshot.
            MenuItem forget = new MenuItem(I18n.t("groups.color.forget"));
            forget.setOnAction(event -> forget(color));
            ContextMenu menu = new ContextMenu(forget);
            region.setOnContextMenuRequested(event -> {
                ProfileMenu.show(menu, region, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }
        return region;
    }

    private void forget(String color) {
        if (settings == null || !settings.removeCustomGroupColor(color)) {
            return;
        }
        // A group painted with it keeps it, so the colour that is still selected
        // has to stay on screen - as the inherited swatch, where it was before
        // it was put on the shelf.
        if (color.equalsIgnoreCase(chosen)) {
            inherited = chosen;
        }
        save();
        rebuildSwatches();
    }

    /**
     * The mixer: a "+" over a real colour picker.
     *
     * <p>The picker itself is present but invisible and deaf to the mouse, and
     * the "+" over it opens it. That way the popup - the platform's own colour
     * chooser, with a hue bar, a saturation square and a hex field - anchors
     * under the swatch it came from, while the row keeps one shape of control
     * rather than a line of squares and then a combo box.
     */
    private Region mixer() {
        picker.setOpacity(0);
        picker.setMouseTransparent(true);
        picker.setFocusTraversable(false);
        picker.setMinSize(SWATCH, SWATCH);
        picker.setPrefSize(SWATCH, SWATCH);
        picker.setMaxSize(SWATCH, SWATCH);
        picker.setOnAction(event -> mixed(picker.getValue()));

        Label plus = new Label("+");
        plus.getStyleClass().add("swatch-add-mark");

        StackPane button = new StackPane(picker, plus);
        button.getStyleClass().addAll("swatch", "swatch-add");
        button.setMinSize(SWATCH, SWATCH);
        button.setPrefSize(SWATCH, SWATCH);
        button.setMaxSize(SWATCH, SWATCH);
        Tooltip.install(button, new Tooltip(I18n.t("groups.color.custom")));
        button.setOnMouseClicked(event -> {
            picker.setValue(Color.web(chosen));
            picker.show();
            event.consume();
        });
        return button;
    }

    /** Takes a colour from the picker: selects it, and puts it on the shelf. */
    private void mixed(Color color) {
        if (color == null) {
            return;
        }
        String hex = hex(color);
        chosen = hex;
        if (settings != null && settings.addCustomGroupColor(hex)) {
            save();
            // On the shelf now, so it no longer needs the inherited slot - and
            // would otherwise be drawn twice.
            if (hex.equals(inherited)) {
                inherited = null;
            }
        } else if (!offered().contains(hex)) {
            inherited = hex;
        }
        rebuildSwatches();
    }

    private void save() {
        if (persist != null) {
            persist.run();
        }
    }

    /**
     * {@code #rrggbb} for a picked colour.
     *
     * <p>Opacity is dropped rather than carried: the value becomes a band behind
     * the cells and a plate under white text, and a half-transparent one of
     * either is a group whose colour depends on what is behind it.
     */
    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }
}
