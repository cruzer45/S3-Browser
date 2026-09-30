package com.s3explorer.ui.dialog;

import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.ui.FormPanel;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Chooses the local destination and conflict handling for a download. */
public final class DownloadDialog {

    public record Result(Path directory, ConflictPolicy policy, boolean rememberAsDefault) {
    }

    private DownloadDialog() {
    }

    public static Result show(Component parent, List<S3ObjectItem> items, String defaultDir, ConflictPolicy policy) {
        JTextField dir = new JTextField(defaultDir, 32);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(dir.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Download To");
            if (chooser.showDialog(parent, "Select") == JFileChooser.APPROVE_OPTION) {
                dir.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel dirRow = new JPanel(new BorderLayout(6, 0));
        dirRow.add(dir, BorderLayout.CENTER);
        dirRow.add(browse, BorderLayout.EAST);

        ButtonGroup group = new ButtonGroup();
        JPanel policies = new JPanel();
        policies.setLayout(new BoxLayout(policies, BoxLayout.Y_AXIS));
        Map<ConflictPolicy, JRadioButton> buttons = new EnumMap<>(ConflictPolicy.class);
        for (ConflictPolicy p : ConflictPolicy.values()) {
            JRadioButton b = new JRadioButton(p.toString(), p == policy);
            group.add(b);
            policies.add(b);
            buttons.put(p, b);
        }
        JCheckBox remember = new JCheckBox("Use these settings as the default");

        long files = items.stream().filter(S3ObjectItem::isFile).count();
        long folders = items.size() - files;
        String what = (files > 0 ? files + " file(s)" : "") + (files > 0 && folders > 0 ? " and " : "")
                + (folders > 0 ? folders + " folder(s)" : "");
        FormPanel form = new FormPanel()
                .row("Download:", new JLabel(what))
                .row("To folder:", dirRow)
                .row("If a file exists:", policies)
                .wide(remember);

        while (true) {
            Object[] options = {"Download", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent, form, "Download", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice != 0) {
                return null;
            }
            Path path = Path.of(dir.getText().trim());
            if (dir.getText().isBlank() || Files.exists(path) && !Files.isDirectory(path)) {
                JOptionPane.showMessageDialog(parent, "Choose a folder to download into.", "Download",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            ConflictPolicy chosen = buttons.entrySet().stream().filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey).findFirst().orElse(ConflictPolicy.RENAME);
            return new Result(path, chosen, remember.isSelected());
        }
    }
}
