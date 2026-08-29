package GTNHNightlyUpdater.Gui;

import GTNHNightlyUpdater.Config.InstanceValidator;
import GTNHNightlyUpdater.Config.MigrationDataCategory;
import GTNHNightlyUpdater.Config.UpdateRequest;
import GTNHNightlyUpdater.DownloadProgressListener;
import GTNHNightlyUpdater.Main;
import GTNHNightlyUpdater.StableUpdater;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The V1 GUI screen: everything needed to run a STABLE update (Migration/In-place, latest
 * stable/beta or an exact pinned version, and one or more Client/Server instances).
 */
@Log4j2(topic = "GTNHNightlyUpdater-Gui")
public class StableView {

    private final ObservableList<InstanceRow> instances = FXCollections.observableArrayList();
    private final VBox instancesBox = new VBox(8);

    private final ToggleGroup versionSourceGroup = new ToggleGroup();
    private final ToggleButton latestStableRadio = new ToggleButton(Messages.get("version.latestStable.label"));
    private final ToggleButton latestBetaRadio = new ToggleButton(Messages.get("version.latestBeta.label"));
    private final ToggleButton exactVersionRadio = new ToggleButton(Messages.get("version.exact.label"));
    private final ComboBox<StableUpdater.ReleaseEntry> exactVersionCombo = new ComboBox<>();

    private final ToggleGroup methodGroup = new ToggleGroup();
    private final ToggleButton migrationRadio = new ToggleButton(Messages.get("method.migration"));
    private final ToggleButton replaceRadio = new ToggleButton(Messages.get("method.replace"));

    private final CheckBox optionsCheck = new CheckBox(Messages.get("advanced.options"));
    private final CheckBox savesCheck = new CheckBox(Messages.get("advanced.saves"));
    private final CheckBox serverListCheck = new CheckBox(Messages.get("advanced.serverList"));
    private final CheckBox otherDataCheck = new CheckBox(Messages.get("advanced.otherData"));
    private final CheckBox customConfigsCheck = new CheckBox(Messages.get("advanced.customConfigs"));
    private final List<Path> selectedCustomConfigFiles = new ArrayList<>();
    private final Label customConfigsFilesLabel = new Label(Messages.get("advanced.customConfigs.none"));

    private final Button startButton = new Button(Messages.get("action.start"));
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label();
    private final ListView<String> logView = new ListView<>();

    private boolean versionsLoaded = false;
    private LogAppender logAppender;
    private String pendingExactVersion;

    public Region build() {
        val root = new VBox(16);
        root.setPadding(new Insets(20));

        root.getChildren().add(buildVersionSourceSection());
        root.getChildren().add(buildMethodSection());
        root.getChildren().add(buildAdvancedSection());
        root.getChildren().add(buildInstancesSection());

        applySavedSettings();

        return root;
    }

    /**
     * Restores the last-used form state (instances, method, version source, advanced options)
     * from disk, falling back to a single empty instance row when nothing was saved yet.
     */
    private void applySavedSettings() {
        GuiSettings settings = GuiSettingsStore.load();

        if (settings == null || settings.getInstances() == null || settings.getInstances().isEmpty()) {
            addInstanceRow();
        } else {
            for (GuiSettings.InstanceSetting saved : settings.getInstances()) {
                UpdateRequest.Side side;
                try {
                    side = UpdateRequest.Side.valueOf(saved.getSide());
                } catch (IllegalArgumentException | NullPointerException e) {
                    side = UpdateRequest.Side.CLIENT;
                }
                addInstanceRow(saved.getPath(), side);
            }
        }

        if (settings == null) {
            return;
        }

        if (settings.isReplace()) {
            replaceRadio.setSelected(true);
        } else {
            migrationRadio.setSelected(true);
        }

        if (settings.getMigrationDataCategories() != null && !settings.getMigrationDataCategories().isEmpty()) {
            Set<String> cats = settings.getMigrationDataCategories();
            optionsCheck.setSelected(cats.contains(MigrationDataCategory.OPTIONS.name()));
            savesCheck.setSelected(cats.contains(MigrationDataCategory.SAVES.name()));
            serverListCheck.setSelected(cats.contains(MigrationDataCategory.SERVER_LIST.name()));
            otherDataCheck.setSelected(cats.contains(MigrationDataCategory.OTHER_USER_DATA.name()));
            customConfigsCheck.setSelected(cats.contains(MigrationDataCategory.CUSTOM_CONFIGS.name()));
        }

        if (settings.getCustomConfigFiles() != null && !settings.getCustomConfigFiles().isEmpty()) {
            selectedCustomConfigFiles.clear();
            settings.getCustomConfigFiles().forEach(p -> selectedCustomConfigFiles.add(Path.of(p)));
            customConfigsFilesLabel.setText(String.format(Messages.get("advanced.customConfigs.selected"),
                    selectedCustomConfigFiles.size(),
                    selectedCustomConfigFiles.stream()
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.joining(", "))));
        }

        if ("LATEST_BETA".equals(settings.getVersionSource())) {
            latestBetaRadio.setSelected(true);
        } else if ("EXACT".equals(settings.getVersionSource()) && settings.getExactVersion() != null) {
            exactVersionRadio.setSelected(true);
            pendingExactVersion = settings.getExactVersion();
        }
    }

    private Region buildVersionSourceSection() {
        latestStableRadio.setToggleGroup(versionSourceGroup);
        latestBetaRadio.setToggleGroup(versionSourceGroup);
        exactVersionRadio.setToggleGroup(versionSourceGroup);
        latestStableRadio.setSelected(true);

        exactVersionCombo.setPromptText(Messages.get("version.combo.prompt"));
        exactVersionCombo.setMaxWidth(Double.MAX_VALUE);
        exactVersionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(StableUpdater.ReleaseEntry entry) {
                if (entry == null) {
                    return "";
                }
                return entry.version() + (entry.stable() ? "" : Messages.get("version.exact.betaSuffix"));
            }

            @Override
            public StableUpdater.ReleaseEntry fromString(String string) {
                return null;
            }
        });
        exactVersionCombo.managedProperty().bind(exactVersionCombo.visibleProperty());
        exactVersionCombo.visibleProperty().bind(exactVersionRadio.selectedProperty());

        val explanation = new Label(Messages.get("version.explanation"));
        val explanationBox = collapsibleExplanation(explanation);

        val segmented = segmentedRow(latestStableRadio, latestBetaRadio, exactVersionRadio);

        val titleRow = cardTitleRow(Messages.get("version.section.title"), explanationBox.getKey());

        val box = new VBox(10, titleRow, segmented, exactVersionCombo, explanationBox.getValue());
        box.getStyleClass().add("gtnh-card");

        loadAvailableVersions();

        return box;
    }

    private void loadAvailableVersions() {
        latestStableRadio.setText(Messages.get("version.latestStable.loading"));
        latestBetaRadio.setText(Messages.get("version.latestBeta.loading"));
        exactVersionCombo.setPromptText(Messages.get("version.combo.loading"));
        Task<List<StableUpdater.ReleaseEntry>> task = new Task<>() {
            @Override
            protected List<StableUpdater.ReleaseEntry> call() throws Exception {
                return new StableUpdater(UpdateRequest.builder().build()).listAvailableVersions();
            }
        };
        task.setOnSucceeded(e -> {
            versionsLoaded = true;
            List<StableUpdater.ReleaseEntry> entries = task.getValue();
            exactVersionCombo.getItems().setAll(entries);
            exactVersionCombo.setPromptText(Messages.get("version.combo.prompt"));
            if (pendingExactVersion != null) {
                String wanted = pendingExactVersion;
                pendingExactVersion = null;
                boolean found = entries.stream()
                        .filter(en -> en.version().equalsIgnoreCase(wanted))
                        .findFirst()
                        .map(en -> {
                            exactVersionCombo.getSelectionModel().select(en);
                            return true;
                        })
                        .orElse(false);
                if (!found && !entries.isEmpty()) {
                    exactVersionCombo.getSelectionModel().selectFirst();
                }
            } else if (!entries.isEmpty()) {
                exactVersionCombo.getSelectionModel().selectFirst();
            }

            entries.stream().filter(StableUpdater.ReleaseEntry::stable).findFirst()
                    .ifPresentOrElse(
                            latest -> latestStableRadio.setText(String.format(Messages.get("version.latestStable.found"), latest.version())),
                            () -> latestStableRadio.setText(Messages.get("version.latestStable.none")));
            if (!entries.isEmpty()) {
                latestBetaRadio.setText(String.format(Messages.get("version.latestBeta.found"), entries.get(0).version()));
            } else {
                latestBetaRadio.setText(Messages.get("version.latestBeta.none"));
            }
        });
        task.setOnFailed(e -> {
            exactVersionCombo.setPromptText(Messages.get("version.combo.error"));
            latestStableRadio.setText(Messages.get("version.latestStable.error"));
            latestBetaRadio.setText(Messages.get("version.latestBeta.error"));
            log.error("Failed to load version history", task.getException());
        });
        Thread thread = new Thread(task, "gtnh-version-history-load");
        thread.setDaemon(true);
        thread.start();
    }

    private Region buildMethodSection() {
        migrationRadio.setToggleGroup(methodGroup);
        replaceRadio.setToggleGroup(methodGroup);
        migrationRadio.setSelected(true);

        val caption = new Label(Messages.get("method.caption"));
        caption.setWrapText(true);
        caption.getStyleClass().addAll("text-muted", "gtnh-explanation");

        val explanation = new Label(Messages.get("method.explanation"));
        val explanationBox = collapsibleExplanation(explanation);

        val segmented = segmentedRow(migrationRadio, replaceRadio);
        val titleRow = cardTitleRow(Messages.get("method.section.title"), explanationBox.getKey());

        val box = new VBox(10, titleRow, segmented, caption, explanationBox.getValue());
        box.getStyleClass().add("gtnh-card");
        return box;
    }

    private Region buildAdvancedSection() {
        optionsCheck.setSelected(true);
        savesCheck.setSelected(true);
        serverListCheck.setSelected(true);
        otherDataCheck.setSelected(true);
        customConfigsCheck.setSelected(false);

        val intro = new Label(Messages.get("advanced.caption"));
        intro.setWrapText(true);
        intro.getStyleClass().add("text-muted");

        val customConfigsWarning = new Label(Messages.get("advanced.customConfigsWarning"));
        customConfigsWarning.setWrapText(true);
        customConfigsWarning.getStyleClass().add("danger");

        customConfigsFilesLabel.setWrapText(true);
        customConfigsFilesLabel.getStyleClass().add("text-muted");

        val chooseFilesButton = new Button(Messages.get("advanced.chooseFiles"), new FontIcon(Feather.FILE_PLUS));
        chooseFilesButton.disableProperty().bind(customConfigsCheck.selectedProperty().not());
        chooseFilesButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(Messages.get("advanced.chooseFiles.dialogTitle"));

            Path initialConfigDir = instances.stream()
                    .filter(row -> row.getSide() == UpdateRequest.Side.CLIENT
                            && row.getPath() != null && !row.getPath().isBlank())
                    .map(row -> InstanceValidator.resolveClientMinecraftDir(Path.of(row.getPath())))
                    .filter(Objects::nonNull)
                    .map(dir -> dir.resolve("config"))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
            if (initialConfigDir != null) {
                chooser.setInitialDirectory(initialConfigDir.toFile());
            }

            Window window = chooseFilesButton.getScene().getWindow();
            List<File> selected = chooser.showOpenMultipleDialog(window);
            if (selected == null) {
                return;
            }

            selected.forEach(f -> {
                Path path = f.toPath();
                if (!selectedCustomConfigFiles.contains(path)) {
                    selectedCustomConfigFiles.add(path);
                }
            });
            customConfigsFilesLabel.setText(selectedCustomConfigFiles.isEmpty()
                    ? Messages.get("advanced.customConfigs.none")
                    : String.format(Messages.get("advanced.customConfigs.selected"),
                            selectedCustomConfigFiles.size(),
                            selectedCustomConfigFiles.stream()
                                    .map(p -> p.getFileName().toString())
                                    .collect(Collectors.joining(", "))));
        });

        val customConfigsBox = new HBox(8, customConfigsCheck, chooseFilesButton);
        customConfigsBox.setAlignment(Pos.CENTER_LEFT);

        val content = new VBox(6, intro, optionsCheck, savesCheck, serverListCheck, otherDataCheck,
                customConfigsBox, customConfigsFilesLabel, customConfigsWarning);
        content.setPadding(new Insets(8, 0, 0, 0));

        val advancedPane = new TitledPane(Messages.get("advanced.title"), content);
        advancedPane.setExpanded(false);
        advancedPane.getStyleClass().add("gtnh-card");
        return advancedPane;
    }

    private Region buildInstancesSection() {
        val addButton = new Button(Messages.get("instances.add"), new FontIcon(Feather.PLUS));
        addButton.setOnAction(e -> addInstanceRow());

        val spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        val headerRow = new HBox(8, sectionTitle(Messages.get("instances.section.title")), spacer, addButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        val box = new VBox(10, headerRow, instancesBox);
        box.getStyleClass().add("gtnh-card");
        return box;
    }

    private void addInstanceRow() {
        addInstanceRow(null, UpdateRequest.Side.CLIENT);
    }

    private void addInstanceRow(String initialPath, UpdateRequest.Side initialSide) {
        InstanceRow row = new InstanceRow();
        if (initialPath != null && !initialPath.isBlank()) {
            row.pathProperty().set(initialPath);
        }
        row.sideProperty().set(initialSide != null ? initialSide : UpdateRequest.Side.CLIENT);
        instances.add(row);

        val pathField = new TextField();
        pathField.setPromptText(Messages.get("instances.pathPrompt"));
        pathField.setEditable(false);
        pathField.textProperty().bind(row.pathProperty());
        HBox.setHgrow(pathField, Priority.ALWAYS);

        val browseButton = iconButton(Feather.FOLDER, Messages.get("instances.browseTooltip"));
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(Messages.get("instances.browseDialogTitle"));
            Window window = browseButton.getScene().getWindow();
            val selected = chooser.showDialog(window);
            if (selected == null) {
                return;
            }

            Path selectedPath = selected.toPath();
            if (row.getSide() == UpdateRequest.Side.CLIENT) {
                Path resolved = InstanceValidator.resolveClientMinecraftDir(selectedPath);
                row.pathProperty().set((resolved != null ? resolved : selectedPath).toString());
            } else {
                row.pathProperty().set(selectedPath.toString());
            }
        });

        val clientToggle = new ToggleButton(Messages.get("instances.client"));
        val serverToggle = new ToggleButton(Messages.get("instances.server"));
        val sideGroup = new ToggleGroup();
        clientToggle.setToggleGroup(sideGroup);
        serverToggle.setToggleGroup(sideGroup);
        (row.getSide() == UpdateRequest.Side.SERVER ? serverToggle : clientToggle).setSelected(true);
        sideGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == serverToggle) {
                row.sideProperty().set(UpdateRequest.Side.SERVER);
            } else if (newToggle == clientToggle) {
                row.sideProperty().set(UpdateRequest.Side.CLIENT);
            }
        });
        val sideSegmented = segmentedRow(clientToggle, serverToggle);

        val removeButton = iconButton(Feather.TRASH_2, Messages.get("instances.removeTooltip"));
        removeButton.getStyleClass().add("danger");

        val rowBox = new HBox(8, pathField, browseButton, sideSegmented, removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.getStyleClass().add("gtnh-instance-row");

        removeButton.setOnAction(e -> {
            instances.remove(row);
            instancesBox.getChildren().remove(rowBox);
        });

        instancesBox.getChildren().add(rowBox);
    }

    /**
     * Start button, progress bar, status label and a collapsible log — built separately from
     * {@link #build()} so the caller can dock it in a fixed bottom bar instead of scrolling away
     * with the rest of the form.
     */
    public Region buildActionBar() {
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setGraphic(new FontIcon(Feather.PLAY));
        startButton.getStyleClass().add("accent");
        startButton.setOnAction(e -> onStart());

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());

        logView.setPrefHeight(200);
        val logPane = new TitledPane(Messages.get("action.logPane"), logView);
        logPane.setExpanded(false);

        val box = new VBox(8, startButton, progressBar, statusLabel, logPane);
        box.getStyleClass().add("gtnh-action-bar");
        return box;
    }

    private void onStart() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n", errors), ButtonType.OK);
            alert.setHeaderText(Messages.get("alert.header"));
            alert.showAndWait();
            return;
        }

        UpdateRequest request = buildRequest();
        saveCurrentSettings();

        startButton.setDisable(true);
        startButton.setText(Messages.get("action.running"));
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText(Messages.get("status.running"));
        statusLabel.getStyleClass().removeAll("success", "danger");
        logView.getItems().clear();

        logAppender = LogAppender.attachToRootLogger(line -> logView.getItems().add(line));

        DownloadProgressListener downloadProgressListener = new DownloadProgressListener() {
            @Override
            public void onProgress(String label, long bytesRead, long totalBytes) {
                Platform.runLater(() -> {
                    if (totalBytes > 0) {
                        double ratio = Math.min(1.0, (double) bytesRead / totalBytes);
                        progressBar.setProgress(ratio);
                        statusLabel.setText(String.format(Messages.get("status.downloading.progress"),
                                label, StableUpdater.humanReadableBytes(bytesRead), StableUpdater.humanReadableBytes(totalBytes),
                                ratio * 100));
                    } else {
                        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        statusLabel.setText(String.format(Messages.get("status.downloading.indeterminate"), label, StableUpdater.humanReadableBytes(bytesRead)));
                    }
                });
            }

            @Override
            public void onPhase(String phase) {
                Platform.runLater(() -> {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    statusLabel.setText(phase);
                });
            }
        };

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Path cacheDir = Main.resolveStableCacheDir();
                new StableUpdater(request, downloadProgressListener).run(cacheDir);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            progressBar.setVisible(false);
            statusLabel.setText(Messages.get("status.success"));
            statusLabel.getStyleClass().add("success");
            finishRun();
        });
        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            Throwable ex = task.getException();
            statusLabel.setText(String.format(Messages.get("status.failed"),
                    ex != null ? ex.getMessage() : Messages.get("status.unknownError")));
            statusLabel.getStyleClass().add("danger");
            log.error("Stable update failed", ex);
            finishRun();
        });

        Thread thread = new Thread(task, "gtnh-stable-update");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishRun() {
        startButton.setDisable(false);
        startButton.setText(Messages.get("action.start"));
        if (logAppender != null) {
            logAppender.detach();
            logAppender = null;
        }
    }

    private List<String> validate() {
        List<String> errors = instances.stream()
                .map(row -> {
                    Path path = row.getPath() == null || row.getPath().isBlank() ? null : Path.of(row.getPath());
                    String error = InstanceValidator.validateMinecraftDir(path);
                    if (error != null) {
                        return error;
                    }
                    if (row.getSide() == UpdateRequest.Side.CLIENT
                            && InstanceValidator.resolveClientMinecraftDir(path) == null) {
                        return InstanceValidator.clientDirErrorMessage(path);
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            errors.add(Messages.get("validate.noInstances"));
        }

        if (exactVersionRadio.isSelected() && exactVersionCombo.getValue() == null) {
            errors.add(Messages.get("validate.noVersionSelected"));
        }

        return errors;
    }

    private UpdateRequest buildRequest() {
        val builder = UpdateRequest.builder()
                .replace(replaceRadio.isSelected())
                .instances(instances.stream()
                        .map(row -> {
                            Path path = Path.of(row.getPath());
                            Path minecraftDir = row.getSide() == UpdateRequest.Side.CLIENT
                                    ? InstanceValidator.resolveClientMinecraftDir(path)
                                    : path;
                            return UpdateRequest.InstanceTarget.builder()
                                    .minecraftDir(minecraftDir)
                                    .side(row.getSide())
                                    .build();
                        })
                        .collect(Collectors.toList()));

        Toggle selectedSource = versionSourceGroup.getSelectedToggle();
        if (selectedSource == latestBetaRadio) {
            builder.beta(true);
        } else if (selectedSource == exactVersionRadio) {
            StableUpdater.ReleaseEntry selected = exactVersionCombo.getValue();
            builder.stableVersion(selected != null ? selected.version() : null);
        }

        builder.migrationDataCategories(selectedMigrationDataCategories());
        builder.customConfigRelativePaths(customConfigRelativePaths());

        return builder.build();
    }

    /**
     * Resolves each user-picked custom config file to a path relative to whichever configured
     * CLIENT instance's {@code .minecraft} folder actually contains it (files not under any
     * configured client instance are silently skipped).
     */
    private List<String> customConfigRelativePaths() {
        List<Path> clientMinecraftDirs = instances.stream()
                .filter(row -> row.getSide() == UpdateRequest.Side.CLIENT
                        && row.getPath() != null && !row.getPath().isBlank())
                .map(row -> InstanceValidator.resolveClientMinecraftDir(Path.of(row.getPath())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<String> result = new ArrayList<>();
        for (Path file : selectedCustomConfigFiles) {
            clientMinecraftDirs.stream()
                    .filter(file::startsWith)
                    .findFirst()
                    .ifPresent(dir -> result.add(dir.relativize(file).toString().replace('\\', '/')));
        }
        return result;
    }

    private Set<MigrationDataCategory> selectedMigrationDataCategories() {
        Set<MigrationDataCategory> categories = EnumSet.noneOf(MigrationDataCategory.class);
        if (optionsCheck.isSelected()) {
            categories.add(MigrationDataCategory.OPTIONS);
        }
        if (savesCheck.isSelected()) {
            categories.add(MigrationDataCategory.SAVES);
        }
        if (serverListCheck.isSelected()) {
            categories.add(MigrationDataCategory.SERVER_LIST);
        }
        if (otherDataCheck.isSelected()) {
            categories.add(MigrationDataCategory.OTHER_USER_DATA);
        }
        if (customConfigsCheck.isSelected()) {
            categories.add(MigrationDataCategory.CUSTOM_CONFIGS);
        }
        return categories;
    }

    /**
     * Snapshots the current form state to disk so it can be restored on the next GUI launch, or
     * immediately after rebuilding this view for a language switch.
     */
    public void persistSettingsSnapshot() {
        saveCurrentSettings();
    }

    /** Current log lines, so a caller rebuilding this view (e.g. for a language switch) can carry them over. */
    public List<String> currentLogLines() {
        return new ArrayList<>(logView.getItems());
    }

    /** Restores previously captured log lines after {@link #buildActionBar()} created a fresh, empty log. */
    public void restoreLogLines(List<String> lines) {
        logView.getItems().setAll(lines);
    }

    private void saveCurrentSettings() {
        GuiSettings settings = new GuiSettings();
        settings.setInstances(instances.stream()
                .map(row -> new GuiSettings.InstanceSetting(row.getPath(), row.getSide().name()))
                .collect(Collectors.toList()));
        settings.setReplace(replaceRadio.isSelected());

        Toggle selectedSource = versionSourceGroup.getSelectedToggle();
        if (selectedSource == latestBetaRadio) {
            settings.setVersionSource("LATEST_BETA");
        } else if (selectedSource == exactVersionRadio) {
            settings.setVersionSource("EXACT");
            StableUpdater.ReleaseEntry selected = exactVersionCombo.getValue();
            settings.setExactVersion(selected != null ? selected.version() : null);
        } else {
            settings.setVersionSource("LATEST_STABLE");
        }

        settings.setMigrationDataCategories(selectedMigrationDataCategories().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        settings.setCustomConfigFiles(selectedCustomConfigFiles.stream()
                .map(Path::toString)
                .collect(Collectors.toList()));

        // Preserve the theme/language preferences, which are saved independently of this form snapshot.
        GuiSettings existing = GuiSettingsStore.load();
        settings.setDarkTheme(existing != null && existing.isDarkTheme());
        settings.setLanguage(existing != null && existing.getLanguage() != null ? existing.getLanguage() : "en");

        GuiSettingsStore.save(settings);
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gtnh-card-title");
        return label;
    }

    /**
     * Lays out {@code buttons} as a segmented control and prevents the JavaFX default of being able
     * to deselect the currently-active button by clicking it again — exactly one stays selected.
     */
    private HBox segmentedRow(ToggleButton... buttons) {
        for (ToggleButton button : buttons) {
            button.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (button.isSelected()) {
                    event.consume();
                }
            });
        }
        // JavaFX CSS doesn't support :first-child/:last-child, so mark the endpoints explicitly
        // for the rounded-corner/border styling in gui.css.
        if (buttons.length > 0) {
            buttons[0].getStyleClass().add("first");
            buttons[buttons.length - 1].getStyleClass().add("last");
        }
        val row = new HBox(buttons);
        row.getStyleClass().add("gtnh-segmented");
        return row;
    }

    private Button iconButton(org.kordamp.ikonli.Ikon icon, String tooltip) {
        Button button = new Button();
        button.setGraphic(new FontIcon(icon));
        button.getStyleClass().add("gtnh-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private HBox cardTitleRow(String title, Button infoToggle) {
        val row = new HBox(6, sectionTitle(title), infoToggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Builds a small "ⓘ" toggle button that shows/hides {@code explanation}, and returns both so
     * the caller can place the toggle next to a section title and the (initially hidden) text
     * wherever it makes sense in that section's layout.
     */
    private java.util.Map.Entry<Button, Label> collapsibleExplanation(Label explanation) {
        explanation.setWrapText(true);
        explanation.getStyleClass().addAll("text-muted", "gtnh-explanation");
        explanation.setVisible(false);
        explanation.managedProperty().bind(explanation.visibleProperty());

        Button infoToggle = new Button();
        infoToggle.setGraphic(new FontIcon(Feather.INFO));
        infoToggle.getStyleClass().add("gtnh-info-toggle");
        infoToggle.setTooltip(new Tooltip(Messages.get("common.moreInfo")));
        infoToggle.setOnAction(e -> explanation.setVisible(!explanation.isVisible()));

        return java.util.Map.entry(infoToggle, explanation);
    }
}
