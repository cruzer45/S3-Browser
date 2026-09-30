package com.s3explorer.ui;

import com.s3explorer.app.AppContext;
import com.s3explorer.util.ErrorTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** Standard message dialogs. Errors are translated into friendly text; details go to the log. */
public final class Dialogs {

    private static final Logger log = LoggerFactory.getLogger(Dialogs.class);

    private Dialogs() {
    }

    /**
     * Shows a translated error.
     *
     * @param action what was being attempted, following "permission to" (e.g. "list this bucket")
     */
    public static void error(Component parent, Throwable error, String action) {
        ErrorTranslator.UserMessage message = ErrorTranslator.translate(error, action);
        log.debug("Showing error to user: {}", message.title(), error);
        JOptionPane.showMessageDialog(parent, wrap(message.message()), message.title(), JOptionPane.ERROR_MESSAGE);
    }

    public static void error(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, wrap(message), title, JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, wrap(message), title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String title, String message, String confirmLabel) {
        Object[] options = {confirmLabel, "Cancel"};
        int result = JOptionPane.showOptionDialog(parent, wrap(message), title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        return result == 0;
    }

    public static String input(Component parent, String title, String label, String initial) {
        Object value = JOptionPane.showInputDialog(parent, label, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
        return value == null ? null : value.toString().trim();
    }

    public static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    /** Wraps long messages so dialogs do not grow wider than the screen. */
    private static Object wrap(String message) {
        if (message == null || message.length() < 80) {
            return message;
        }
        JTextArea area = new JTextArea(message);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        area.setColumns(42);
        area.setSize(area.getPreferredSize());
        return area;
    }

    public static String appTitle(String suffix) {
        return suffix == null ? AppContext.APP_NAME : suffix + " - " + AppContext.APP_NAME;
    }
}
