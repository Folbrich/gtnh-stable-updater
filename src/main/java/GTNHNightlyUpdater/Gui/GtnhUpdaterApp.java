package GTNHNightlyUpdater.Gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.val;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * JavaFX entry point for the GUI version of the updater. Started by {@code Main.main} when no
 * CLI arguments are given.
 */
public class GtnhUpdaterApp extends Application {

    private BorderPane root;
    private ScrollPane scrollPane;
    private StableView stableView;

    @Override
    public void start(Stage stage) {
        GuiSettings settings = GuiSettingsStore.load();
        boolean dark = settings != null && settings.isDarkTheme();
        applyTheme(dark);
        Messages.setLanguage(settings != null ? settings.getLanguage() : "en");

        stableView = new StableView();
        val formContent = stableView.build();
        scrollPane = new ScrollPane(formContent);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("edge-to-edge");

        root = new BorderPane();
        root.setTop(buildHeader(dark));
        root.setCenter(scrollPane);
        root.setBottom(stableView.buildActionBar());

        val scene = new Scene(root, 960, 780);
        scene.getStylesheets().add(getClass().getResource("/gui/gui.css").toExternalForm());

        stage.setTitle("GTNH Stable Updater");
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.show();
    }

    private Region buildHeader(boolean initiallyDark) {
        val titleBox = new VBox(2,
                labelWithStyle("GTNH Stable Updater", "gtnh-header-title"),
                labelWithStyle(Messages.get("app.subtitle"), "gtnh-header-subtitle"));

        val spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        val languageToggle = new Button();
        languageToggle.setText(Messages.isEnglish() ? "EN" : "DE");
        languageToggle.getStyleClass().add("gtnh-theme-toggle");
        languageToggle.setTooltip(new Tooltip(Messages.get("language.toggleTooltip")));
        languageToggle.setOnAction(e -> switchLanguage(Messages.isEnglish() ? "de" : "en"));

        val themeIcon = new FontIcon(initiallyDark ? Feather.SUN : Feather.MOON);
        val themeToggle = new Button();
        themeToggle.setGraphic(themeIcon);
        themeToggle.getStyleClass().add("gtnh-theme-toggle");
        val themeTooltip = new Tooltip(initiallyDark ? Messages.get("theme.toLight") : Messages.get("theme.toDark"));
        themeToggle.setTooltip(themeTooltip);

        val darkState = new boolean[]{initiallyDark};
        themeToggle.setOnAction(e -> {
            darkState[0] = !darkState[0];
            applyTheme(darkState[0]);
            themeIcon.setIconCode(darkState[0] ? Feather.SUN : Feather.MOON);
            themeTooltip.setText(darkState[0] ? Messages.get("theme.toLight") : Messages.get("theme.toDark"));
            GuiSettingsStore.updateDarkTheme(darkState[0]);
        });

        val header = new HBox(12, titleBox, spacer, languageToggle, themeToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("gtnh-header");
        header.setPadding(new Insets(0));
        return header;
    }

    /**
     * Persists the current form state, switches the active language, then rebuilds the header,
     * form and action bar so every label reflects the new language — {@link StableView}'s text
     * nodes are built once with fixed strings rather than bound reactively, so a full rebuild is
     * the simplest way to re-render them; the just-saved settings are restored automatically via
     * {@link StableView#build()}'s existing settings-reload logic.
     */
    private void switchLanguage(String languageCode) {
        stableView.persistSettingsSnapshot();
        val logLines = stableView.currentLogLines();
        Messages.setLanguage(languageCode);
        GuiSettingsStore.updateLanguage(languageCode);

        GuiSettings settings = GuiSettingsStore.load();
        boolean dark = settings != null && settings.isDarkTheme();

        stableView = new StableView();
        val formContent = stableView.build();
        scrollPane.setContent(formContent);

        root.setTop(buildHeader(dark));
        root.setBottom(stableView.buildActionBar());
        stableView.restoreLogLines(logLines);
    }

    private Label labelWithStyle(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void applyTheme(boolean dark) {
        setUserAgentStylesheet(dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
    }
}
