package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.LauncherSettings;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.launch.JavaRuntimes;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.net.ProxyChoice;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settings window.
 *
 * <h2>Why one window</h2>
 *
 * <p>Every setting the launcher already had lived somewhere else: the language in
 * the header, the Java download policy in a "never ask again" button inside a
 * prompt, the CurseForge key in the mod browser, and the rest only in
 * {@code launcher.json}. That is fine for a setting you meet once and awkward for
 * one you want to change: it has to be found again, and some of them could not be
 * found at all without a text editor. So they are all here, reachable from either
 * interface, and grouped by the question they answer rather than by the file they
 * are stored in.
 *
 * <h2>Save writes, Cancel writes nothing</h2>
 *
 * <p>The same rule as {@link ProfileDialog}. Nothing on these tabs takes effect
 * as it is typed, because half of it cannot be undone by typing it back - a
 * cleared client id is not the same as the one that was there, and a grid that
 * has already been narrowed has already moved profiles.
 *
 * <h2>The grid size is not a setting</h2>
 *
 * <p>The two spinners for the inventory grid are on the Interface tab, but the
 * numbers live in {@link ProfileLayout} with the cells they describe, because
 * narrowing the grid has to move the profiles that were in the removed column
 * and can fail. So the dialog asks the layout to change and reports a refusal
 * instead of writing a number that the cells would then contradict.
 */
public final class SettingsDialog {

    private final LauncherSettings settings;
    private final ProfileLayout layout;
    private final GameDirs dirs;

    // Interface
    private final ComboBox<Language> languageBox = new ComboBox<>();
    private final Spinner<Integer> columnsSpinner = new Spinner<>();
    private final Spinner<Integer> rowsSpinner = new Spinner<>();
    private final Spinner<Integer> splashSpinner = new Spinner<>();

    // Game
    private final CheckBox keepOpen = new CheckBox();
    private final CheckBox minimiseToTray = new CheckBox();
    private final CheckBox showAllVersions = new CheckBox();

    /**
     * Read every installed file before every launch.
     *
     * <p>In the Game tab rather than beside the credential settings, because
     * what it changes is what happens when Play is pressed. It is the one
     * setting here that trades a measurable amount of the user's time for a
     * threat model, so the note under it says which trade, in those terms.
     */
    private final CheckBox verifyEveryLaunch = new CheckBox();

    /**
     * Ask before a mod other mods need is switched off or deleted.
     *
     * <p>Here rather than only in the dialog itself, because the dialog offers
     * to stop showing itself and a switch that can only be turned off from a
     * window that no longer appears is a switch that cannot be turned back on.
     */
    private final CheckBox warnAboutDependents = new CheckBox();

    /** How much of the data folder the kept mod logos may fill, in megabytes. */
    private final Spinner<Integer> modIconCache = new Spinner<>();

    // Java
    private final ComboBox<JavaRuntimes.DownloadPolicy> javaPolicyBox = new ComboBox<>();

    // Network and mods
    private final Spinner<Integer> concurrencySpinner = new Spinner<>();

    private final ComboBox<ProxyChoice.Mode> proxyModeBox = new ComboBox<>();
    private final TextField proxyHost = new TextField();
    private final Spinner<Integer> proxyPort = new Spinner<>();
    private final TextField proxyUser = new TextField();
    private final PasswordField proxyPassword = new PasswordField();
    private final Label proxyResult = new Label();

    /**
     * True once the password field has been typed in.
     *
     * <p>The saved password is never read back into the field - a window that
     * displays a stored secret is a window that leaks it to whoever walks past.
     * So an untouched field means "leave what is stored alone", and only a
     * deliberate edit replaces it.
     */
    private boolean proxyPasswordEdited;
    private final PasswordField curseForgeKey = new PasswordField();

    // Accounts
    //
    // The Azure client id is deliberately not here. It identifies the launcher
    // to Microsoft, not the user to the launcher: every copy of this build signs
    // in as the same registered application, and a field inviting somebody to
    // change it is a field whose only possible use is to break their sign-in.
    // It stays in launcher.json for whoever forks the project.
    private final ComboBox<String> signInMethodBox = new ComboBox<>();
    private final CheckBox secureHandshake = new CheckBox();
    private final CheckBox fileCredentialStore = new CheckBox();

    /** What changed, for the caller to act on after the dialog closes. */
    public static final class Result {

        private final boolean languageChanged;
        private final boolean gridChanged;
        private final List<String> refused;

        private Result(boolean languageChanged, boolean gridChanged, List<String> refused) {
            this.languageChanged = languageChanged;
            this.gridChanged = gridChanged;
            this.refused = List.copyOf(refused);
        }

        /** True when the interface language is now a different one. */
        public boolean languageChanged() {
            return languageChanged;
        }

        /** True when the grid changed shape, so both views need redrawing. */
        public boolean gridChanged() {
            return gridChanged;
        }

        /** Messages for changes that could not be made, ready to show. */
        public List<String> refused() {
            return refused;
        }
    }

    private final com.hexadron.launcher.auth.secret.SecretStore secrets;

    public SettingsDialog(LauncherSettings settings, ProfileLayout layout, GameDirs dirs,
                          com.hexadron.launcher.auth.secret.SecretStore secrets) {
        this.settings = settings;
        this.layout = layout;
        this.dirs = dirs;
        this.secrets = secrets;
    }

    /**
     * Opens the dialog.
     *
     * @return what changed when Save was pressed, empty when it was cancelled
     */
    public Optional<Result> show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("settings.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ButtonType save = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        dialog.getDialogPane().setContent(buildTabs());
        // Sized for the longest note on the widest tab. A dialog that opens
        // exactly as wide as its shortest tab makes every wrapped note on every
        // other tab a single ellipsised line, which is how these read before.
        dialog.getDialogPane().setPrefSize(820, 620);
        dialog.getDialogPane().setMinWidth(660);
        Theme.apply(dialog.getDialogPane());

        prefill();

        // The Test button applies what is on screen so it can try it. Cancel has
        // to undo that, or a route the user rejected is the one the launcher
        // keeps using until it is restarted.
        ProxyChoice before = Http.proxy();

        if (dialog.showAndWait().filter(button -> button == save).isEmpty()) {
            Http.useProxy(before, before.wantsAuthentication() ? storedProxyPassword() : null);
            return Optional.empty();
        }
        return Optional.of(apply());
    }

    // ---------------------------------------------------------------- form

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("settings.tab.interface", interfaceTab()),
                tab("settings.tab.game", gameTab()),
                tab("settings.tab.java", javaTab()),
                tab("settings.tab.network", networkTab()),
                tab("settings.tab.accounts", accountsTab()),
                tab("settings.tab.data", dataTab()));
        return tabs;
    }

    private static Tab tab(String key, GridPane content) {
        Tab tab = new Tab(I18n.t(key), content);
        tab.setClosable(false);
        return tab;
    }

    private GridPane interfaceTab() {
        languageBox.setItems(FXCollections.observableArrayList(Language.all()));
        languageBox.setMaxWidth(Double.MAX_VALUE);

        spinner(columnsSpinner, ProfileLayout.MIN_COLUMNS, ProfileLayout.MAX_COLUMNS,
                layout.columns());
        spinner(rowsSpinner, ProfileLayout.MIN_ROWS, ProfileLayout.MAX_ROWS, layout.rows());
        spinner(splashSpinner, 0, 15, settings.splashMinimumMillis() / 1000);

        GridPane grid = form();
        int row = 0;
        grid.addRow(row++, label("label.language"), languageBox);
        grid.addRow(row++, label("settings.grid.columns"), columnsSpinner);
        grid.addRow(row++, label("settings.grid.rows"), rowsSpinner);
        grid.addRow(row++, new Label(), note("settings.grid.note"));
        grid.addRow(row++, label("settings.splash"), splashSpinner);
        grid.addRow(row, new Label(), note("settings.splash.note"));
        return grid;
    }

    private GridPane gameTab() {
        keepOpen.setText(I18n.t("settings.keepOpen"));
        minimiseToTray.setText(I18n.t("settings.tray"));
        showAllVersions.setText(I18n.t("editor.showAll"));
        verifyEveryLaunch.setText(I18n.t("settings.verify"));

        GridPane grid = form();
        int row = 0;
        grid.addRow(row++, new Label(), keepOpen);
        grid.addRow(row++, new Label(), minimiseToTray);
        grid.addRow(row++, new Label(), note("settings.tray.note"));
        grid.addRow(row++, new Label(), showAllVersions);
        grid.addRow(row++, new Label(), verifyEveryLaunch);
        grid.addRow(row, new Label(), note("settings.verify.note"));
        return grid;
    }

    private GridPane javaTab() {
        javaPolicyBox.setItems(FXCollections.observableArrayList(
                JavaRuntimes.DownloadPolicy.values()));
        javaPolicyBox.setMaxWidth(Double.MAX_VALUE);
        javaPolicyBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(JavaRuntimes.DownloadPolicy policy) {
                if (policy == null) {
                    return "";
                }
                return switch (policy) {
                    case ASK -> I18n.t("settings.java.ask");
                    case ALWAYS -> I18n.t("settings.java.always");
                    case NEVER -> I18n.t("settings.java.never");
                };
            }

            @Override
            public JavaRuntimes.DownloadPolicy fromString(String text) {
                return null;
            }
        });

        GridPane grid = form();
        grid.addRow(0, label("settings.java"), javaPolicyBox);
        grid.addRow(1, new Label(), note("settings.java.note"));
        return grid;
    }

    private GridPane networkTab() {
        spinner(concurrencySpinner, 1, 32, settings.downloadConcurrency());
        curseForgeKey.setPromptText(I18n.t("settings.curseforge.prompt"));

        proxyModeBox.setItems(FXCollections.observableArrayList(ProxyChoice.Mode.values()));
        proxyModeBox.setMaxWidth(Double.MAX_VALUE);
        proxyModeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProxyChoice.Mode mode) {
                if (mode == null) {
                    return "";
                }
                return I18n.t(switch (mode) {
                    case SYSTEM -> "settings.proxy.system";
                    case DIRECT -> "settings.proxy.direct";
                    case MANUAL -> "settings.proxy.manual";
                });
            }

            @Override
            public ProxyChoice.Mode fromString(String text) {
                return null;
            }
        });
        proxyModeBox.valueProperty().addListener((observable, previous, value) -> refreshProxy());

        proxyHost.setPromptText("127.0.0.1");
        spinner(proxyPort, 1, 65535, settings.proxy().port());
        proxyUser.setPromptText(I18n.t("settings.proxy.optional"));
        proxyPassword.setPromptText(I18n.t("settings.proxy.optional"));
        proxyPassword.textProperty().addListener(
                (observable, previous, value) -> proxyPasswordEdited = true);

        proxyResult.getStyleClass().add("muted");
        proxyResult.setWrapText(true);
        proxyResult.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        Button test = new Button(I18n.t("settings.proxy.test"));
        test.setOnAction(event -> testConnection(test));

        warnAboutDependents.setText(I18n.t("settings.modWarnings"));
        spinner(modIconCache, LauncherSettings.MOD_ICON_CACHE_MIN,
                LauncherSettings.MOD_ICON_CACHE_MAX, settings.modIconCacheMegabytes());

        GridPane grid = form();
        int row = 0;
        grid.addRow(row++, label("settings.concurrency"), concurrencySpinner);
        grid.addRow(row++, new Label(), note("settings.concurrency.note"));
        grid.addRow(row++, new Label(), warnAboutDependents);
        grid.addRow(row++, new Label(), note("settings.modWarnings.note"));
        grid.addRow(row++, label("settings.modIconCache"), modIconCache);
        grid.addRow(row++, new Label(), note("settings.modIconCache.note"));
        grid.addRow(row++, label("settings.curseforge"), curseForgeKey);
        grid.addRow(row++, new Label(), note("mods.curseforge.key.body"));
        grid.addRow(row++, new Label(), new javafx.scene.control.Separator());
        grid.addRow(row++, label("settings.proxy"), proxyModeBox);
        grid.addRow(row++, new Label(), note("settings.proxy.note"));
        grid.addRow(row++, label("settings.proxy.host"), proxyHost);
        grid.addRow(row++, label("settings.proxy.port"), proxyPort);
        grid.addRow(row++, label("settings.proxy.user"), proxyUser);
        grid.addRow(row++, label("settings.proxy.password"), proxyPassword);
        grid.addRow(row++, new Label(), note("settings.proxy.privacy"));
        grid.addRow(row++, new Label(), test);
        grid.addRow(row, new Label(), proxyResult);
        return grid;
    }

    private void refreshProxy() {
        boolean manual = proxyModeBox.getValue() == ProxyChoice.Mode.MANUAL;
        proxyHost.setDisable(!manual);
        proxyPort.setDisable(!manual);
        proxyUser.setDisable(!manual);
        proxyPassword.setDisable(!manual);
    }

    /**
     * Tries the route that is on screen, not the one that is saved.
     *
     * <p>The point of the button is to find out whether what was just typed
     * works, before it is saved and before the next install fails on it. So the
     * proxy is applied first, the manifest host is fetched, and the previous
     * route is put back if the window is then cancelled.
     */
    private void testConnection(Button button) {
        ProxyChoice choice = proxyFromFields();
        if (!choice.isUsable()) {
            proxyResult.setText(I18n.t("settings.proxy.incomplete"));
            return;
        }

        button.setDisable(true);
        proxyResult.setText(I18n.t("settings.proxy.testing"));
        String password = proxyPasswordEdited ? proxyPassword.getText() : storedProxyPassword();

        Thread worker = new Thread(() -> {
            String message;
            try {
                Http.useProxy(choice, password);
                Http.getString(com.hexadron.launcher.meta.VersionManifest.MANIFEST_URL);
                message = I18n.t("settings.proxy.ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = e.toString();
            } catch (Exception e) {
                message = I18n.t("settings.proxy.failed",
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String shown = message;
            javafx.application.Platform.runLater(() -> {
                button.setDisable(false);
                proxyResult.setText(shown);
            });
        }, "proxy-test");
        worker.setDaemon(true);
        worker.start();
    }

    private ProxyChoice proxyFromFields() {
        ProxyChoice.Mode mode = proxyModeBox.getValue();
        return new ProxyChoice(mode == null ? ProxyChoice.Mode.SYSTEM : mode,
                proxyHost.getText(), value(proxyPort), proxyUser.getText());
    }

    private String storedProxyPassword() {
        if (secrets == null) {
            return null;
        }
        try {
            return secrets.load(com.hexadron.launcher.core.LauncherService.PROXY_PASSWORD_KEY)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private GridPane accountsTab() {
        signInMethodBox.setItems(FXCollections.observableArrayList("browser", "deviceCode"));
        signInMethodBox.setMaxWidth(Double.MAX_VALUE);
        signInMethodBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return "deviceCode".equals(value)
                        ? I18n.t("settings.signIn.deviceCode")
                        : I18n.t("settings.signIn.browser");
            }

            @Override
            public String fromString(String text) {
                return null;
            }
        });
        secureHandshake.setText(I18n.t("settings.handshake"));
        fileCredentialStore.setText(I18n.t("settings.fileStore"));

        GridPane grid = form();
        int row = 0;
        grid.addRow(row++, label("settings.signIn"), signInMethodBox);
        grid.addRow(row++, new Label(), note("settings.signIn.note"));
        grid.addRow(row++, new Label(), secureHandshake);
        grid.addRow(row++, new Label(), note("settings.handshake.note"));
        grid.addRow(row++, new Label(), fileCredentialStore);
        grid.addRow(row, new Label(), note("settings.fileStore.note"));
        return grid;
    }

    private GridPane dataTab() {
        TextField path = new TextField(dirs.root().toString());
        path.setEditable(false);
        HBox.setHgrow(path, Priority.ALWAYS);

        javafx.scene.control.Button open = new javafx.scene.control.Button(
                I18n.t("action.openFolder"));
        open.setOnAction(event -> openDataFolder());

        HBox line = new HBox(8, path, open);
        line.setAlignment(Pos.CENTER_LEFT);

        TextField logPath = new TextField(dirs.logs().toString());
        logPath.setEditable(false);
        HBox.setHgrow(logPath, Priority.ALWAYS);

        Button openLogs = new Button(I18n.t("action.openFolder"));
        openLogs.setOnAction(event -> openFolder(dirs.logs(), logPath));

        HBox logLine = new HBox(8, logPath, openLogs);
        logLine.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = form();
        grid.addRow(0, label("settings.dataFolder"), line);
        grid.addRow(1, new Label(), note("settings.dataFolder.note"));
        grid.addRow(2, label("settings.logs"), logLine);
        grid.addRow(3, new Label(), note("settings.logs.note"));
        return grid;
    }

    /**
     * Opens the data folder in the platform's file manager.
     *
     * <p>Failure is reported in the field rather than as an error: some Linux
     * sessions have no AWT desktop integration at all, and the path is on screen
     * beside the button in any case.
     */
    private void openDataFolder() {
        openFolder(dirs.root(), null);
    }

    /** The same, for any folder the tab shows. */
    private void openFolder(java.nio.file.Path folder, TextField shownIn) {
        try {
            java.nio.file.Files.createDirectories(folder);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            }
        } catch (Exception ignored) {
            // The path stays visible; nothing else to do from here.
            if (shownIn != null) {
                shownIn.selectAll();
            }
        }
    }

    private static GridPane form() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form");
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(200);
        labels.setPrefWidth(200);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        fields.setMinWidth(320);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static Label label(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("form-label");
        return label;
    }

    /**
     * An explanatory line under a field.
     *
     * <p>{@code minHeight = USE_PREF_SIZE} is the whole reason this is a method.
     * A wrapping label reports a preferred height that depends on the width it
     * ends up with, and a GridPane row sized from the unresolved height gives it
     * one line - so the text wrapped correctly and then had nowhere to wrap to,
     * and every note showed as one line ending in an ellipsis. Asking the row to
     * be at least the preferred height is what makes the wrap visible.
     */
    private static Label note(String key) {
        Label note = new Label(I18n.t(key));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        note.setMaxWidth(Double.MAX_VALUE);
        note.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return note;
    }

    private static void spinner(Spinner<Integer> spinner, int low, int high, int value) {
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                low, high, Math.max(low, Math.min(high, value))));
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
    }

    // ---------------------------------------------------------------- values

    private void prefill() {
        languageBox.setValue(I18n.current());
        keepOpen.setSelected(settings.keepOpenWhilePlaying());
        minimiseToTray.setSelected(settings.minimiseToTrayWhilePlaying());
        showAllVersions.setSelected(settings.showAllVersions());
        verifyEveryLaunch.setSelected(settings.verifyEveryLaunch());
        warnAboutDependents.setSelected(settings.warnAboutDependents());
        modIconCache.getValueFactory().setValue(settings.modIconCacheMegabytes());
        javaPolicyBox.setValue(settings.javaDownloadPolicy());
        curseForgeKey.setText(settings.curseForgeApiKey());
        signInMethodBox.setValue(settings.usesBrowserSignIn() ? "browser" : "deviceCode");
        secureHandshake.setSelected(settings.secureLaunchHandshake());
        fileCredentialStore.setSelected(settings.useFileCredentialStore());

        proxyModeBox.setValue(settings.proxy().mode());
        proxyHost.setText(settings.proxy().host());
        proxyUser.setText(settings.proxy().user());
        // Never the password: see proxyPasswordEdited.
        proxyPasswordEdited = false;
        refreshProxy();
    }

    private Result apply() {
        List<String> refused = new ArrayList<>();

        Language language = languageBox.getValue();
        boolean languageChanged = language != null && language != I18n.current();
        if (languageChanged) {
            I18n.use(language);
            settings.language(language.code());
        }

        settings.keepOpenWhilePlaying(keepOpen.isSelected());
        settings.minimiseToTrayWhilePlaying(minimiseToTray.isSelected());
        settings.showAllVersions(showAllVersions.isSelected());
        settings.verifyEveryLaunch(verifyEveryLaunch.isSelected());
        settings.warnAboutDependents(warnAboutDependents.isSelected());
        settings.modIconCacheMegabytes(value(modIconCache));
        settings.javaDownloadPolicy(javaPolicyBox.getValue());
        settings.downloadConcurrency(value(concurrencySpinner));
        settings.curseForgeApiKey(curseForgeKey.getText());
        settings.microsoftSignInMethod(signInMethodBox.getValue());
        settings.secureLaunchHandshake(secureHandshake.isSelected());
        settings.useFileCredentialStore(fileCredentialStore.isSelected());
        settings.splashMinimumMillis(value(splashSpinner) * 1000);

        ProxyChoice choice = proxyFromFields();
        if (choice.isUsable()) {
            settings.proxy(choice);
            if (proxyPasswordEdited && secrets != null) {
                try {
                    if (proxyPassword.getText() == null || proxyPassword.getText().isEmpty()) {
                        secrets.delete(
                                com.hexadron.launcher.core.LauncherService.PROXY_PASSWORD_KEY);
                    } else {
                        secrets.store(
                                com.hexadron.launcher.core.LauncherService.PROXY_PASSWORD_KEY,
                                proxyPassword.getText());
                    }
                } catch (IOException e) {
                    refused.add(I18n.t("settings.proxy.notsaved"));
                }
            }
        } else {
            // Refused rather than half-applied: a proxy mode with no address is
            // a setting that cannot do anything, and silently reverting to
            // direct on a network where direct fails looks like the proxy being
            // ignored.
            refused.add(I18n.t("settings.proxy.incomplete"));
        }

        // The grid last, and reported rather than forced. Narrowing has to find
        // free cells for the profiles it displaces, and when it cannot, the
        // honest answer is that the number did not change.
        boolean gridChanged = false;
        int wantColumns = value(columnsSpinner);
        int wantRows = value(rowsSpinner);
        if (wantColumns != layout.columns()) {
            if (layout.columns(wantColumns)) {
                gridChanged = true;
            } else {
                refused.add(I18n.t("settings.grid.refusedColumns", layout.columns()));
            }
        }
        if (wantRows != layout.rows()) {
            if (layout.rows(wantRows)) {
                gridChanged = true;
            } else {
                refused.add(I18n.t("settings.grid.refusedRows", layout.rows()));
            }
        }
        return new Result(languageChanged, gridChanged, refused);
    }

    /**
     * The value of an editable spinner.
     *
     * <p>An editable spinner keeps the typed text and the value apart until the
     * field loses focus, so pressing Save straight after typing would otherwise
     * read the number that was there before. Committing the text first is what
     * makes the dialog agree with what is on screen.
     */
    private static int value(Spinner<Integer> spinner) {
        try {
            spinner.getEditor().commitValue();
        } catch (RuntimeException ignored) {
            // Unparseable text: the factory keeps the last good value.
        }
        Integer current = spinner.getValue();
        return current == null ? 0 : current;
    }
}
