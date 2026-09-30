package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.config.AppPaths;
import com.s3explorer.ui.Icons;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/** About box and keyboard shortcut reference. */
public final class AboutDialog {

    private static final String[][] SHORTCUTS = {
            {"Ctrl+C / Ctrl+X / Ctrl+V", "Copy / cut / paste objects"},
            {"Ctrl+A", "Select all"},
            {"Delete", "Delete"},
            {"F2", "Rename"},
            {"F5", "Refresh"},
            {"Ctrl+F", "Search"},
            {"Ctrl+U", "Upload files"},
            {"Ctrl+Shift+U", "Upload folder"},
            {"Ctrl+D", "Download"},
            {"Ctrl+Shift+N", "New folder"},
            {"Alt+Enter", "Properties"},
            {"Space", "Preview"},
            {"Ctrl+L", "Edit the path"},
            {"Alt+Left / Alt+Right", "Back / forward"},
            {"Alt+Up / Backspace", "Up one folder"},
            {"Enter", "Open"},
            {"Ctrl+K", "Connections"},
            {"Ctrl+,", "Settings"},
    };

    private AboutDialog() {
    }

    public static void showAbout(Component parent) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        JLabel logo = new JLabel(Icons.logo());
        JLabel text = new JLabel("<html><center>Version " + AppContext.VERSION
                + "<br>A desktop file browser for Amazon S3 and S3-compatible storage."
                + "<br><br>Java " + System.getProperty("java.version")
                + "<br>Logs: " + AppPaths.logsDir() + "</center></html>", SwingConstants.CENTER);
        panel.add(logo, BorderLayout.CENTER);
        panel.add(text, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(parent, panel, "About " + AppContext.APP_NAME, JOptionPane.PLAIN_MESSAGE);
    }

    public static void showShortcuts(Component parent) {
        DefaultTableModel model = new DefaultTableModel(SHORTCUTS, new String[]{"Shortcut", "Action"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 420));
        JOptionPane.showMessageDialog(parent, scroll, "Keyboard Shortcuts", JOptionPane.PLAIN_MESSAGE);
    }
}
