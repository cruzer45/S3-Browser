package com.s3explorer.ui.dialog;

import com.s3explorer.config.SettingsStore;
import com.s3explorer.service.TransferPlanner;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;

/** Confirms a drag-and-drop upload: sources, destination, file count and total size. */
public final class UploadConfirmationDialog {

    private UploadConfirmationDialog() {
    }

    public static boolean show(Component parent, TransferPlanner.UploadPlan plan, String connectionName,
                               SettingsStore settings) {
        JList<String> sources = new JList<>(plan.sources().stream().map(Path::toString).toArray(String[]::new));
        sources.setVisibleRowCount(Math.min(6, Math.max(2, plan.sources().size())));
        JScrollPane scroll = new JScrollPane(sources);
        scroll.setPreferredSize(new Dimension(460, Math.min(140, 26 * plan.sources().size() + 8)));
        JCheckBox dontAsk = new JCheckBox("Don't ask again for drag-and-drop uploads");

        String prefix = plan.destination().key().isEmpty() ? "/" : plan.destination().key();
        FormPanel form = new FormPanel()
                .row("Source:", scroll)
                .row("Connection:", new JLabel(connectionName))
                .row("Bucket:", new JLabel(plan.destination().bucket()))
                .row("Prefix:", new JLabel(prefix))
                .row("Files:", new JLabel(String.format("%,d", plan.fileCount())
                        + (plan.emptyFolders().isEmpty() ? "" : " (+" + plan.emptyFolders().size() + " empty folders)")))
                .row("Total size:", new JLabel(FileSizeFormatter.format(plan.totalBytes())))
                .wide(dontAsk);
        Object[] options = {"Upload", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, form, "Upload", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0 && dontAsk.isSelected()) {
            settings.get().general.confirmDragDropUpload = false;
            settings.save();
        }
        return choice == 0;
    }
}
