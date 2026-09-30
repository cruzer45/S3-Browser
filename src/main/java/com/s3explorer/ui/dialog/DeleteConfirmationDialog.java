package com.s3explorer.ui.dialog;

import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.service.TransferPlanner;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import java.util.stream.Collectors;

/** Confirms a delete with object count, total size, bucket, prefix and versioning status. */
public final class DeleteConfirmationDialog {

    private DeleteConfirmationDialog() {
    }

    public static boolean show(Component parent, TransferPlanner.DeletePlan plan, List<S3ObjectItem> items) {
        JLabel headline = new JLabel(plan.objectCount() == 0
                ? "Delete empty folder" + (items.size() == 1 ? "?" : "s?")
                : String.format("Delete %,d object%s?", plan.objectCount(), plan.objectCount() == 1 ? "" : "s"));
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            headline.setFont(font.deriveFont(Font.BOLD, font.getSize2D() + 2f));
        }
        String selection = items.stream().limit(5).map(i -> i.name() + (i.isFile() ? "" : "/"))
                .collect(Collectors.joining(", ")) + (items.size() > 5 ? ", …" : "");
        boolean versioned = "Enabled".equalsIgnoreCase(plan.versioningStatus());
        String warning = versioned
                ? "Versioning is enabled: previous versions are kept and delete markers are added."
                : "This operation may not be reversible unless S3 versioning is enabled.";
        String prefix = plan.scope().key().isEmpty() ? "/" : plan.scope().key();

        FormPanel form = new FormPanel()
                .wide(headline)
                .row("Selected:", new JLabel(selection))
                .row("Total size:", new JLabel(FileSizeFormatter.format(plan.totalBytes())))
                .row("Bucket:", new JLabel(plan.scope().bucket()))
                .row("Prefix:", new JLabel(prefix))
                .row("Versioning:", new JLabel(plan.versioningStatus()))
                .wide(new JLabel("<html><div style='width:340px'>" + warning + "</div></html>"));
        Object[] options = {"Delete", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, form, "Confirm Delete", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0;
    }
}
