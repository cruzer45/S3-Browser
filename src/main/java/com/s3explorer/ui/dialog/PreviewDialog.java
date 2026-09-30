package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.ui.browser.PreviewPanel;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;

/** A resizable window showing a single object's preview. */
public final class PreviewDialog {

    private PreviewDialog() {
    }

    public static void show(Component parent, AppContext ctx, S3Connection connection, S3Path path) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Preview - " + path.name(), JDialog.ModalityType.MODELESS);
        PreviewPanel preview = new PreviewPanel();
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel content = new JPanel(new BorderLayout());
        content.add(preview, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(close), BorderLayout.SOUTH);
        dialog.setContentPane(content);
        DialogSupport.install(dialog, close);
        dialog.setSize(820, 620);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        preview.load(ctx, connection, path);
    }
}
