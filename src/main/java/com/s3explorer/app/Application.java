package com.s3explorer.app;

import com.s3explorer.config.AppPaths;
import com.s3explorer.ui.MainWindow;
import com.s3explorer.ui.ThemeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Entry point. */
public final class Application {

    private Application() {
    }

    public static void main(String[] args) {
        // Must be set before the first logger is created: logback.xml reads it for the log directory.
        if (System.getProperty(AppPaths.HOME_PROPERTY) == null) {
            System.setProperty(AppPaths.HOME_PROPERTY, AppPaths.baseDir().toString());
        }
        AppPaths.ensureDirectories();
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");

        Logger log = LoggerFactory.getLogger(Application.class);
        log.info("Starting {} {} on Java {} ({})", AppContext.APP_NAME, AppContext.VERSION,
                System.getProperty("java.version"), System.getProperty("os.name"));

        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                log.error("Uncaught exception on thread {}", thread.getName(), error));

        AppContext context = new AppContext();
        SwingUtilities.invokeLater(() -> {
            try {
                ThemeManager.apply(context.settings.get().appearance);
                MainWindow window = new MainWindow(context);
                window.showWindow();
            } catch (RuntimeException e) {
                log.error("Could not start the user interface", e);
                JOptionPane.showMessageDialog(null, "S3 Explorer could not start:\n" + e.getMessage(),
                        AppContext.APP_NAME, JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
