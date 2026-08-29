package GTNHNightlyUpdater.Gui;

import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.function.Consumer;

/**
 * Bridges Log4j2 log events into a JavaFX UI, so the existing {@code log.info(...)} calls in
 * {@code StableUpdater} show up live in the GUI's log console without any changes to that class.
 */
public class LogAppender extends AbstractAppender {

    private final Consumer<String> sink;

    private LogAppender(String name, Layout<String> layout, Consumer<String> sink) {
        super(name, null, layout, false, null);
        this.sink = sink;
    }

    @Override
    public void append(LogEvent event) {
        String formatted = new String(getLayout().toByteArray(event));
        Platform.runLater(() -> sink.accept(formatted.stripTrailing()));
    }

    /**
     * Registers a new appender on the root logger that forwards every log line to {@code sink}
     * (called on the JavaFX application thread). Returns the appender so it can be detached again.
     */
    public static LogAppender attachToRootLogger(Consumer<String> sink) {
        Layout<String> layout = PatternLayout.newBuilder()
                .withPattern("[%d{HH:mm:ss}] [%p] - %m%n")
                .build();
        LogAppender appender = new LogAppender("GuiLogAppender", layout, sink);
        appender.start();

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();

        return appender;
    }

    public void detach() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        config.getRootLogger().removeAppender(getName());
        this.stop();
        context.updateLoggers();
    }
}
