package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.model.S3BucketInfo;
import com.s3explorer.model.S3Path;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.util.Background;
import com.s3explorer.util.ErrorTranslator;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;

/** Chooses a destination (connection, bucket, prefix) for Copy To / Move To. */
public final class CopyMoveDialog extends JDialog {

    public record Destination(String connectionId, S3Path folder) {
    }

    private final AppContext ctx;
    private final JComboBox<ConnectionProfile> connection = new JComboBox<>();
    private final JComboBox<String> bucket = new JComboBox<>();
    private final JTextField prefix = new JTextField(30);
    private final JLabel status = new JLabel(" ");
    private Destination result;
    private int loadGeneration;

    public static Destination show(Component parent, AppContext ctx, boolean move, String connectionId,
                                   S3Path current) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        CopyMoveDialog dialog = new CopyMoveDialog(owner, ctx, move, connectionId, current);
        dialog.setVisible(true);
        return dialog.result;
    }

    private CopyMoveDialog(Window owner, AppContext ctx, boolean move, String connectionId, S3Path current) {
        super(owner, move ? "Move To" : "Copy To", ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        bucket.setEditable(true);
        prefix.putClientProperty("JTextField.placeholderText", "folder/subfolder/ (empty for bucket root)");
        for (ConnectionProfile p : ctx.connections.profiles()) {
            connection.addItem(p);
            if (p.id.equals(connectionId)) {
                connection.setSelectedItem(p);
            }
        }
        if (current != null) {
            prefix.setText(current.key());
        }
        connection.addActionListener(e -> loadBuckets(null));

        FormPanel form = new FormPanel()
                .row("Connection:", connection)
                .row("Bucket:", bucket)
                .row("Folder:", prefix)
                .wide(status);
        JButton ok = new JButton(move ? "Move" : "Copy");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(ok, cancel), BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, ok);
        loadBuckets(current == null ? null : current.bucket());
        pack();
        setLocationRelativeTo(owner);
    }

    private void loadBuckets(String select) {
        ConnectionProfile p = (ConnectionProfile) connection.getSelectedItem();
        bucket.removeAllItems();
        if (p == null) {
            return;
        }
        if (ctx.connections.needsSecret(p)) {
            status.setText("Connect to \"" + p.name + "\" once to enter its secret key.");
            return;
        }
        int gen = ++loadGeneration;
        status.setText("Loading buckets…");
        Background.run(() -> {
            S3Connection conn = ctx.connections.get(p.id);
            return ctx.browser.listBuckets(conn);
        }, buckets -> {
            if (gen != loadGeneration) {
                return;
            }
            for (S3BucketInfo b : buckets) {
                bucket.addItem(b.name());
            }
            if (select != null) {
                bucket.setSelectedItem(select);
            }
            status.setText(" ");
        }, error -> {
            if (gen == loadGeneration) {
                status.setText(ErrorTranslator.translate(error, "list buckets").title()
                        + " You can still type a bucket name.");
            }
        });
    }

    private void accept() {
        ConnectionProfile p = (ConnectionProfile) connection.getSelectedItem();
        Object b = bucket.getEditor().getItem();
        String bucketName = b == null ? "" : b.toString().trim();
        if (p == null || bucketName.isEmpty()) {
            Dialogs.error(this, "Choose a destination", "Select a connection and bucket.");
            return;
        }
        if (ctx.connections.needsSecret(p)) {
            Dialogs.error(this, "Credentials required", "Connect to \"" + p.name + "\" first to enter its secret key.");
            return;
        }
        String key = prefix.getText().trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        result = new Destination(p.id, S3Path.folder(bucketName, key));
        dispose();
    }
}
