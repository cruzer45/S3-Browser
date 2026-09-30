package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.MetadataEdit;
import com.s3explorer.model.ObjectProperties;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.Background;
import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.ErrorTranslator;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows properties of a file, folder or bucket. For files, the Metadata tab can edit
 * system headers and custom metadata (applied by copying the object onto itself).
 */
public final class ObjectPropertiesDialog extends JDialog {

    private final AppContext ctx;
    private final S3Connection connection;
    private final S3ObjectItem item;
    private final Runnable onChanged;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField contentType = new JTextField(30);
    private final JTextField contentEncoding = new JTextField(30);
    private final JTextField contentDisposition = new JTextField(30);
    private final JTextField contentLanguage = new JTextField(30);
    private final JTextField cacheControl = new JTextField(30);
    private final DefaultTableModel metaModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0);
    private final JTable metaTable = new JTable(metaModel);
    private final JButton edit = new JButton("Edit Metadata");
    private final JButton save = new JButton("Save");
    private final JButton addRow = new JButton("Add");
    private final JButton removeRow = new JButton("Remove");
    private Future<?> pending;

    public static void show(Component parent, AppContext ctx, S3Connection connection, S3ObjectItem item,
                            Runnable onChanged) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        new ObjectPropertiesDialog(owner, ctx, connection, item, onChanged).setVisible(true);
    }

    private ObjectPropertiesDialog(Window owner, AppContext ctx, S3Connection connection, S3ObjectItem item,
                                   Runnable onChanged) {
        super(owner, "Properties - " + item.name(), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.connection = connection;
        this.item = item;
        this.onChanged = onChanged;

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        tabs.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        switch (item.kind()) {
            case FILE -> buildFile();
            case FOLDER -> buildFolder();
            case BUCKET -> buildBucket();
            default -> {
            }
        }
        content.add(item.isFile() ? DialogSupport.buttonBar(edit, save, close) : DialogSupport.buttonBar(close),
                BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, close);
        setSize(640, 560);
        setLocationRelativeTo(owner);
    }

    @Override
    public void dispose() {
        closed.set(true);
        if (pending != null) {
            pending.cancel(true);
        }
        super.dispose();
    }

    private static JTextField value(String text) {
        JTextField f = new JTextField(text == null ? "" : text);
        f.setEditable(false);
        f.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        f.setOpaque(false);
        return f;
    }

    private static JPanel loading() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel("Loading…", Icons.get("refresh"), JLabel.CENTER), BorderLayout.CENTER);
        return p;
    }

    // --- file ----------------------------------------------------------------------------

    private void buildFile() {
        tabs.addTab("General", loading());
        tabs.addTab("Metadata", buildMetadataTab());
        setMetadataEditable(false);
        save.setEnabled(false);
        edit.setEnabled(false);
        edit.addActionListener(e -> setMetadataEditable(true));
        save.addActionListener(e -> saveMetadata());
        loadFile();
    }

    private void loadFile() {
        S3Path path = item.path();
        pending = Background.run(() -> ctx.browser.properties(connection, path), this::showFile,
                error -> showError(0, error, "read this object's properties"));
    }

    private void showFile(ObjectProperties p) {
        FormPanel form = new FormPanel()
                .row("Bucket:", value(p.path().bucket()))
                .row("Key:", value(p.path().key()))
                .row("S3 URI:", value(p.path().toUri()))
                .row("Object URL:", value(p.objectUrl()))
                .row("Size:", value(FileSizeFormatter.format(p.size()) + String.format(" (%,d bytes)", p.size())))
                .row("Last modified:", value(DateTimeUtils.formatPrecise(p.lastModified())))
                .row("ETag:", value(p.eTag()))
                .row("Content type:", value(p.contentType()))
                .row("Content encoding:", value(p.contentEncoding()))
                .row("Cache control:", value(p.cacheControl()))
                .row("Storage class:", value(p.storageClass()))
                .row("Version ID:", value(p.versionId() == null ? "(none)" : p.versionId()))
                .row("Encryption:", value(p.serverSideEncryption() == null ? "None"
                        : p.serverSideEncryption() + (p.kmsKeyId() == null ? "" : " (" + p.kmsKeyId() + ")")))
                .fillRemaining();
        tabs.setComponentAt(0, new JScrollPane(form));

        contentType.setText(nullToEmpty(p.contentType()));
        contentEncoding.setText(nullToEmpty(p.contentEncoding()));
        contentDisposition.setText(nullToEmpty(p.contentDisposition()));
        contentLanguage.setText(nullToEmpty(p.contentLanguage()));
        cacheControl.setText(nullToEmpty(p.cacheControl()));
        metaModel.setRowCount(0);
        p.metadata().forEach((k, v) -> metaModel.addRow(new Object[]{k, v}));
        edit.setEnabled(true);
        setMetadataEditable(false);
    }

    private JPanel buildMetadataTab() {
        FormPanel system = new FormPanel()
                .row("Content-Type:", contentType)
                .row("Content-Encoding:", contentEncoding)
                .row("Content-Disposition:", contentDisposition)
                .row("Content-Language:", contentLanguage)
                .row("Cache-Control:", cacheControl);
        addRow.addActionListener(e -> {
            metaModel.addRow(new Object[]{"", ""});
            int row = metaModel.getRowCount() - 1;
            metaTable.changeSelection(row, 0, false, false);
            if (metaTable.editCellAt(row, 0) && metaTable.getEditorComponent() != null) {
                metaTable.getEditorComponent().requestFocusInWindow();
            }
        });
        removeRow.addActionListener(e -> {
            if (metaTable.isEditing()) {
                metaTable.getCellEditor().stopCellEditing();
            }
            int[] rows = metaTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                metaModel.removeRow(metaTable.convertRowIndexToModel(rows[i]));
            }
        });
        JPanel tableButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        tableButtons.add(addRow);
        tableButtons.add(removeRow);
        JPanel custom = new JPanel(new BorderLayout());
        custom.setBorder(BorderFactory.createTitledBorder("Custom metadata (x-amz-meta-*)"));
        custom.add(new JScrollPane(metaTable), BorderLayout.CENTER);
        custom.add(tableButtons, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(system, BorderLayout.NORTH);
        panel.add(custom, BorderLayout.CENTER);
        JLabel note = new JLabel("<html><div style='width:520px'>Saving rewrites the object in place with the new "
                + "metadata. In versioned buckets this creates a new version.</div></html>");
        note.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(note, BorderLayout.SOUTH);
        return panel;
    }

    private void setMetadataEditable(boolean editable) {
        for (JTextField f : new JTextField[]{contentType, contentEncoding, contentDisposition, contentLanguage,
                cacheControl}) {
            f.setEditable(editable);
        }
        metaTable.setEnabled(editable);
        addRow.setEnabled(editable);
        removeRow.setEnabled(editable);
        save.setEnabled(editable);
        edit.setEnabled(!editable && edit.isEnabled());
        if (editable) {
            tabs.setSelectedIndex(1);
        }
    }

    private void saveMetadata() {
        if (metaTable.isEditing()) {
            metaTable.getCellEditor().stopCellEditing();
        }
        Map<String, String> custom = new LinkedHashMap<>();
        for (int r = 0; r < metaModel.getRowCount(); r++) {
            Object k = metaModel.getValueAt(r, 0);
            Object v = metaModel.getValueAt(r, 1);
            if (k != null && !k.toString().isBlank()) {
                custom.put(k.toString(), v == null ? "" : v.toString());
            }
        }
        MetadataEdit editData = new MetadataEdit(contentType.getText(), contentEncoding.getText(),
                contentDisposition.getText(), contentLanguage.getText(), cacheControl.getText(), custom);
        save.setEnabled(false);
        Background.run(() -> {
            ctx.metadata.update(connection, item.path(), editData);
            return null;
        }, ignored -> {
            onChanged.run();
            loadFile();
        }, error -> {
            save.setEnabled(true);
            Dialogs.error(this, error, "update objects in " + item.path().bucket());
        });
    }

    // --- folder and bucket -------------------------------------------------------------------

    private void buildFolder() {
        S3Path path = item.path();
        JTextField count = value("Calculating…");
        JTextField size = value("Calculating…");
        FormPanel form = new FormPanel()
                .row("Bucket:", value(path.bucket()))
                .row("Prefix:", value(path.key()))
                .row("S3 URI:", value(path.toUri()))
                .row("Objects:", count)
                .row("Total size:", size)
                .fillRemaining();
        tabs.addTab("General", form);
        pending = Background.run(() -> ctx.browser.folderStats(connection, path, closed::get), stats -> {
            count.setText(String.format("%,d", stats.objectCount()));
            size.setText(FileSizeFormatter.format(stats.totalSize()) + String.format(" (%,d bytes)", stats.totalSize()));
        }, error -> {
            String message = ErrorTranslator.translate(error, "list this folder").title();
            count.setText(message);
            size.setText("");
        });
    }

    private void buildBucket() {
        String bucket = item.path().bucket();
        JTextField region = value("Loading…");
        JTextField versioning = value("Loading…");
        FormPanel form = new FormPanel()
                .row("Bucket:", value(bucket))
                .row("S3 URI:", value("s3://" + bucket + "/"))
                .row("Created:", value(item.lastModified() == null ? "" : DateTimeUtils.formatPrecise(item.lastModified())))
                .row("Region:", region)
                .row("Versioning:", versioning)
                .fillRemaining();
        tabs.addTab("General", form);
        pending = Background.run(() -> new String[]{
                ctx.browser.bucketRegion(connection, bucket),
                ctx.browser.versioningStatus(connection, bucket)}, values -> {
            region.setText(values[0]);
            versioning.setText(values[1]);
        }, error -> region.setText(ErrorTranslator.translate(error, "read this bucket").title()));
    }

    private void showError(int tab, Throwable error, String action) {
        ErrorTranslator.UserMessage m = ErrorTranslator.translate(error, action);
        JLabel label = new JLabel("<html><center><b>" + m.title() + "</b><br>" + m.message() + "</center></html>",
                JLabel.CENTER);
        tabs.setComponentAt(tab, label);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
