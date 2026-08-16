package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.Loaders;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.util.Arguments;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The one place an instance is configured, for both creating and editing.
 *
 * <p>This replaces editing the fields of the selected profile directly in the
 * main window. Live-editing a list entry has no cancel: a mistyped name is
 * already saved by the time it is noticed, and there is no moment at which the
 * values can be validated together. A dialog gives the edit a beginning and an
 * end - Save writes, Cancel writes nothing - which is also how Prism Launcher
 * and MultiMC have always handled instance settings.
 */
public final class ProfileDialog {

    /** Loads the loader builds for a Minecraft version. Called off the UI thread. */
    @FunctionalInterface
    public interface LoaderVersionSource {
        List<String> load(LoaderType loader, String minecraftVersion) throws Exception;
    }

    /** Supplies the Minecraft version list, already filtered by the caller's preference. */
    @FunctionalInterface
    public interface VersionSource {
        List<String> load(boolean includeAll) throws Exception;
    }

    private final TextField nameField = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final CheckBox showAllVersions = new CheckBox();
    private final ComboBox<LoaderType> loaderBox = new ComboBox<>();
    private final ComboBox<String> loaderVersionBox = new ComboBox<>();
    private final Slider memorySlider = new Slider(1024, 32768, 4096);
    private final Label memoryLabel = new Label();
    private final TextField javaField = new TextField();
    private final TextField jvmArgumentsField = new TextField();

    private final VersionSource versions;
    private final LoaderVersionSource loaderVersions;
    private final boolean startWithAllVersions;

    public ProfileDialog(VersionSource versions,
                         LoaderVersionSource loaderVersions,
                         boolean startWithAllVersions) {
        this.versions = versions;
        this.loaderVersions = loaderVersions;
        this.startWithAllVersions = startWithAllVersions;
    }

    /**
     * Opens the dialog.
     *
     * @param existing the profile to edit, or null to create one
     * @return the profile when Save was pressed, empty when it was cancelled.
     *         For an edit, the same instance is returned, already updated.
     */
    public Optional<Profile> show(Window owner, Profile existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null
                ? I18n.t("profiles.new.title")
                : I18n.t("profiles.edit.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ButtonType save = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        dialog.getDialogPane().setContent(buildForm());
        dialog.getDialogPane().setPrefWidth(560);
        Theme.apply(dialog.getDialogPane());

        prefill(existing);
        loadVersionsAsync();

        // Validation happens before the dialog closes, so a rejected form stays
        // open with what the user typed still in it.
        dialog.getDialogPane().lookupButton(save).addEventFilter(
                javafx.event.ActionEvent.ACTION, event -> {
                    String problem = validate();
                    if (problem != null) {
                        event.consume();
                        Alert alert = new Alert(Alert.AlertType.WARNING, problem);
                        alert.initOwner(dialog.getDialogPane().getScene().getWindow());
                        alert.setHeaderText(null);
                        alert.showAndWait();
                    }
                });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != save) {
            return Optional.empty();
        }
        return Optional.of(apply(existing));
    }

    // ---------------------------------------------------------------- form

    private GridPane buildForm() {
        nameField.setPromptText(I18n.t("editor.name.prompt"));

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.valueProperty().addListener((observable, previous, value) -> loadLoaderVersionsAsync());

        showAllVersions.setText(I18n.t("editor.showAll"));
        showAllVersions.setSelected(startWithAllVersions);
        showAllVersions.setOnAction(event -> loadVersionsAsync());

        loaderBox.setItems(FXCollections.observableArrayList(Loaders.allLoaders()));
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        // Without this the list shows the enum constants - FABRIC, NEOFORGE -
        // rather than the names the loaders are actually called by.
        loaderBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LoaderType loader) {
                return loader == null ? "" : loader.displayName();
            }

            @Override
            public LoaderType fromString(String text) {
                return Loaders.allLoaders().stream()
                        .filter(candidate -> candidate.displayName().equals(text))
                        .findFirst().orElse(null);
            }
        });
        loaderBox.valueProperty().addListener((observable, previous, value) -> loadLoaderVersionsAsync());

        loaderVersionBox.setMaxWidth(Double.MAX_VALUE);
        loaderVersionBox.setEditable(true);
        loaderVersionBox.setPromptText(I18n.t("dialog.loaderVersion.auto"));

        memorySlider.setMajorTickUnit(2048);
        memorySlider.setBlockIncrement(512);
        memorySlider.valueProperty().addListener((observable, previous, value) ->
                memoryLabel.setText(I18n.t("unit.megabytes", String.valueOf(rounded(value.doubleValue())))));
        memoryLabel.setMinWidth(90);

        javaField.setPromptText(I18n.t("editor.java.prompt"));
        jvmArgumentsField.setPromptText(I18n.t("dialog.jvmArgs.prompt"));

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form");
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 16, 8, 16));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(150);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        grid.addRow(row++, formLabel(I18n.t("editor.name")), nameField);
        grid.addRow(row++, formLabel(I18n.t("editor.version")), versionBox);
        grid.addRow(row++, new Label(), showAllVersions);
        grid.addRow(row++, formLabel(I18n.t("editor.loader")), loaderBox);
        grid.addRow(row++, formLabel(I18n.t("editor.loaderVersion")), loaderVersionBox);

        HBox memory = new HBox(10, memorySlider, memoryLabel);
        HBox.setHgrow(memorySlider, Priority.ALWAYS);
        grid.addRow(row++, formLabel(I18n.t("editor.memory")), memory);

        grid.addRow(row++, formLabel(I18n.t("editor.java")), javaField);
        grid.addRow(row, formLabel(I18n.t("dialog.jvmArgs")), jvmArgumentsField);
        return grid;
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    private void prefill(Profile existing) {
        if (existing == null) {
            loaderBox.setValue(LoaderType.FABRIC);
            memorySlider.setValue(Profile.defaultMemoryMegabytes());
            memoryLabel.setText(I18n.t("unit.megabytes",
                    String.valueOf(Profile.defaultMemoryMegabytes())));
            return;
        }
        nameField.setText(existing.name());
        versionBox.setValue(existing.minecraftVersion());
        loaderBox.setValue(existing.loader());
        loaderVersionBox.setValue(existing.loaderVersion());
        memorySlider.setValue(existing.memoryMegabytes());
        memoryLabel.setText(I18n.t("unit.megabytes", String.valueOf(existing.memoryMegabytes())));
        javaField.setText(existing.javaPath() == null ? "" : existing.javaPath());
        jvmArgumentsField.setText(Arguments.join(existing.extraJvmArguments()));
    }

    private String validate() {
        if (nameField.getText() == null || nameField.getText().isBlank()) {
            return I18n.t("dialog.name.required");
        }
        if (versionBox.getValue() == null || versionBox.getValue().isBlank()) {
            return I18n.t("dialog.version.required");
        }
        return null;
    }

    private Profile apply(Profile existing) {
        String name = nameField.getText().trim();
        String version = versionBox.getValue();
        LoaderType loader = loaderBox.getValue() == null ? LoaderType.VANILLA : loaderBox.getValue();

        Profile profile = existing == null
                ? Profile.create(name, version, loader)
                : existing.name(name).minecraftVersion(version).loader(loader);

        String loaderVersion = loaderVersionBox.getValue();
        profile.loaderVersion(loaderVersion == null || loaderVersion.isBlank() ? null : loaderVersion.trim());
        profile.memoryMegabytes(rounded(memorySlider.getValue()));
        profile.javaPath(javaField.getText());
        profile.extraJvmArguments(Arguments.split(jvmArgumentsField.getText()));
        return profile;
    }


    private static int rounded(double value) {
        return (int) (Math.round(value / 256.0) * 256);
    }

    /** True when the dialog was left showing every channel, so the caller can persist it. */
    public boolean showsAllVersions() {
        return showAllVersions.isSelected();
    }

    // ---------------------------------------------------------------- loading

    private void loadVersionsAsync() {
        boolean all = showAllVersions.isSelected();
        runOffThread(() -> {
            List<String> ids = versions.load(all);
            Platform.runLater(() -> {
                String previous = versionBox.getValue();
                versionBox.setItems(FXCollections.observableArrayList(ids));
                if (previous != null && ids.contains(previous)) {
                    versionBox.setValue(previous);
                } else if (!ids.isEmpty()) {
                    versionBox.setValue(ids.get(0));
                }
            });
        });
    }

    private void loadLoaderVersionsAsync() {
        LoaderType loader = loaderBox.getValue();
        String version = versionBox.getValue();
        if (loader == null || version == null || loader == LoaderType.VANILLA) {
            Platform.runLater(() -> {
                loaderVersionBox.setItems(FXCollections.observableArrayList());
                loaderVersionBox.setDisable(loader == LoaderType.VANILLA);
            });
            return;
        }
        runOffThread(() -> {
            List<String> ids = loaderVersions.load(loader, version);
            Platform.runLater(() -> {
                loaderVersionBox.setDisable(false);
                String previous = loaderVersionBox.getValue();
                loaderVersionBox.setItems(FXCollections.observableArrayList(ids));
                if (previous != null && ids.contains(previous)) {
                    loaderVersionBox.setValue(previous);
                }
            });
        });
    }

    /**
     * Runs a network call off the UI thread.
     *
     * <p>Failures are swallowed on purpose: the dialog stays usable with an
     * empty list, and a blank loader version already means "the recommended
     * build", which the installer resolves at install time. An error popup for
     * a list that is only a convenience would be worse than the empty list.
     */
    private static void runOffThread(ThrowingRunnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Leave the list empty.
            }
        }, "hexadron-dialog");
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
