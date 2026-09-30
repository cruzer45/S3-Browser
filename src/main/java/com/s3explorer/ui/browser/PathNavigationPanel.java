package com.s3explorer.ui.browser;

import com.s3explorer.model.S3Path;
import com.s3explorer.ui.Icons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Breadcrumb bar ("Buckets › bucket › folder") that turns into an editable s3:// path on
 * click or Ctrl+L, plus a quick filter for the current listing.
 */
public class PathNavigationPanel extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel crumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JTextField editor = new JTextField();
    private final JTextField filter = new JTextField(16);
    private Consumer<S3Path> onNavigate = p -> { };
    private Consumer<String> onFilter = f -> { };
    private S3Path location;
    private boolean enabled;

    public PathNavigationPanel() {
        super(new BorderLayout(8, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        crumbs.setOpaque(false);
        crumbs.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        crumbs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                startEditing();
            }
        });
        editor.putClientProperty("JTextField.placeholderText", "s3://bucket/prefix/");
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    commitEdit();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    stopEditing();
                }
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                stopEditing();
            }
        });
        center.setOpaque(false);
        center.add(crumbs, "crumbs");
        center.add(editor, "editor");

        filter.putClientProperty("JTextField.placeholderText", "Filter");
        filter.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.setToolTipText("Filter the current folder by name");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onFilter.accept(filter.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onFilter.accept(filter.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onFilter.accept(filter.getText());
            }
        });

        add(center, BorderLayout.CENTER);
        add(filter, BorderLayout.EAST);
        setLocation(false, null);
    }

    public void onNavigate(Consumer<S3Path> handler) {
        this.onNavigate = handler;
    }

    public void onFilter(Consumer<String> handler) {
        this.onFilter = handler;
    }

    public void clearFilter() {
        filter.setText("");
    }

    public void focusFilter() {
        filter.requestFocusInWindow();
        filter.selectAll();
    }

    /** @param connected whether a connection is active; {@code location} null means the bucket list */
    public void setLocation(boolean connected, S3Path location) {
        this.enabled = connected;
        this.location = location;
        crumbs.removeAll();
        if (!connected) {
            JLabel label = new JLabel("Not connected");
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
            crumbs.add(label);
        } else {
            crumbs.add(crumb("Buckets", null, "connection"));
            if (location != null) {
                for (S3Path p : location.ancestry()) {
                    crumbs.add(separator());
                    crumbs.add(crumb(p.name(), p, p.isBucketRoot() ? "bucket" : "folder"));
                }
            }
        }
        crumbs.revalidate();
        crumbs.repaint();
        cards.show(center, "crumbs");
    }

    private JButton crumb(String text, S3Path target, String icon) {
        JButton b = new JButton(text, Icons.get(icon));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setCursor(Cursor.getDefaultCursor());
        b.addActionListener(e -> onNavigate.accept(target));
        return b;
    }

    private static JLabel separator() {
        JLabel l = new JLabel("›");
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        return l;
    }

    public void startEditing() {
        if (!enabled) {
            return;
        }
        editor.setText(location == null ? "s3://" : location.toUri());
        cards.show(center, "editor");
        editor.requestFocusInWindow();
        editor.selectAll();
    }

    private void stopEditing() {
        cards.show(center, "crumbs");
    }

    private void commitEdit() {
        String text = editor.getText().trim();
        stopEditing();
        if (text.isEmpty() || text.equalsIgnoreCase("s3://") || text.equals("/")) {
            onNavigate.accept(null);
            return;
        }
        try {
            S3Path parsed = S3Path.parse(text);
            onNavigate.accept(S3Path.folder(parsed.bucket(), parsed.key()));
        } catch (IllegalArgumentException e) {
            // Leave the location unchanged on an unparseable path.
        }
    }
}
