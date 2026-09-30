package com.s3explorer.ui;

import com.s3explorer.util.FileSizeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** "Connected | Production AWS | my-bucket | 42 objects | 3 selected, 421 MB | 2 transfers" */
public class StatusBar extends JPanel {

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private final JLabel connection = new JLabel();
    private final JLabel profile = new JLabel();
    private final JLabel bucket = new JLabel();
    private final JLabel objects = new JLabel();
    private final JLabel selection = new JLabel();
    private final JLabel transfers = new JLabel();

    public StatusBar() {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        add(connection);
        add(separator());
        add(profile);
        add(separator());
        add(bucket);
        add(separator());
        add(objects);
        add(separator());
        add(selection);
        add(separator());
        add(transfers);
        setConnection(ConnectionState.DISCONNECTED, null);
        setBucket(null);
        setObjects(0, false);
        setSelection(0, 0);
        setTransfers(0);
    }

    private static Component separator() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 14));
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        wrap.setOpaque(false);
        wrap.add(s);
        return wrap;
    }

    public void setConnection(ConnectionState state, String profileName) {
        String text;
        Color color;
        switch (state) {
            case CONNECTED -> {
                text = "Connected";
                color = new Color(0x59A869);
            }
            case CONNECTING -> {
                text = "Connecting…";
                color = new Color(0xEDA200);
            }
            case ERROR -> {
                text = "Connection error";
                color = new Color(0xDB5860);
            }
            default -> {
                text = "Not connected";
                color = Color.GRAY;
            }
        }
        connection.setText(text);
        connection.setIcon(new Dot(color));
        profile.setText(profileName == null ? "No connection" : profileName);
    }

    public void setBucket(String name) {
        bucket.setText(name == null ? "No bucket" : name);
    }

    public void setObjects(int count, boolean loading) {
        objects.setText(String.format("%,d item%s", count, count == 1 ? "" : "s") + (loading ? " (loading…)" : ""));
    }

    public void setSelection(int count, long bytes) {
        selection.setText(count == 0 ? "Nothing selected"
                : count + " selected" + (bytes > 0 ? ", " + FileSizeFormatter.format(bytes) : ""));
    }

    public void setTransfers(int pending) {
        transfers.setText(pending == 0 ? "No active transfers" : pending + " transfer" + (pending == 1 ? "" : "s"));
    }

    /** A small status dot. */
    private record Dot(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 1, y + 2, 8, 8);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }
}
