package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.config.AppSettings;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.ui.FormPanel;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;

/** Application settings: general, transfers, S3 defaults and appearance. */
public final class SettingsDialog extends JDialog {

    private final AppContext ctx;
    private boolean applied;

    // General
    private final JTextField downloadDir = new JTextField(28);
    private final JComboBox<AppSettings.DoubleClickAction> doubleClick = new JComboBox<>(AppSettings.DoubleClickAction.values());
    private final JComboBox<ConflictPolicy> conflictPolicy = new JComboBox<>(ConflictPolicy.values());
    private final JCheckBox showHidden = new JCheckBox("Show hidden objects (names starting with \".\")");
    private final JCheckBox restoreWindow = new JCheckBox("Restore previous window position");
    private final JCheckBox restoreConnection = new JCheckBox("Reconnect to the previous connection on startup");
    private final JCheckBox confirmDrop = new JCheckBox("Confirm drag-and-drop uploads");
    private final JCheckBox persistTransfers = new JCheckBox("Keep unfinished transfers for resume after restart");
    private final JCheckBox bucketAdmin = new JCheckBox("Enable bucket creation and deletion");
    private final JSpinner previewKb = new JSpinner(new SpinnerNumberModel(5120, 16, 1024 * 1024, 256));

    // Transfers
    private final JSpinner concurrent = new JSpinner(new SpinnerNumberModel(3, 1, 16, 1));
    private final JSpinner threshold = new JSpinner(new SpinnerNumberModel(100, 5, 5120, 5));
    private final JSpinner partSize = new JSpinner(new SpinnerNumberModel(16, 5, 5120, 1));
    private final JSpinner parallelParts = new JSpinner(new SpinnerNumberModel(4, 1, 32, 1));
    private final JSpinner retryCount = new JSpinner(new SpinnerNumberModel(3, 0, 20, 1));
    private final JSpinner retryDelay = new JSpinner(new SpinnerNumberModel(2, 0, 300, 1));
    private final JSpinner connectTimeout = new JSpinner(new SpinnerNumberModel(10, 1, 600, 1));
    private final JSpinner requestTimeout = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
    private final JSpinner bandwidth = new JSpinner(new SpinnerNumberModel(0L, 0L, 10_000_000L, 256L));

    // S3
    private final JTextField defaultRegion = new JTextField(16);
    private final JTextField defaultEndpoint = new JTextField(28);
    private final JCheckBox pathStyle = new JCheckBox("Use path-style addressing for new connections");
    private final JComboBox<Object> defaultConnection = new JComboBox<>();

    // Appearance
    private final JComboBox<AppSettings.Theme> theme = new JComboBox<>(AppSettings.Theme.values());
    private final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(0, 0, 32, 1));
    private final JComboBox<AppSettings.TableDensity> density = new JComboBox<>(AppSettings.TableDensity.values());
    private final JCheckBox previewPane = new JCheckBox("Show the preview pane");

    public static boolean show(Component parent, AppContext ctx) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        SettingsDialog dialog = new SettingsDialog(owner, ctx);
        dialog.setVisible(true);
        return dialog.applied;
    }

    private SettingsDialog(Window owner, AppContext ctx) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        load(ctx.settings.get());

        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(downloadDir.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showDialog(this, "Select") == JFileChooser.APPROVE_OPTION) {
                downloadDir.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel dirRow = new JPanel(new BorderLayout(6, 0));
        dirRow.add(downloadDir, BorderLayout.CENTER);
        dirRow.add(browse, BorderLayout.EAST);
        fontSize.setToolTipText("0 uses the default size");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", new JScrollPane(new FormPanel()
                .row("Default download folder:", dirRow)
                .row("When a download exists:", conflictPolicy)
                .row("Double-click on a file:", doubleClick)
                .row("Preview size limit (KB):", previewKb)
                .wide(showHidden)
                .wide(confirmDrop)
                .wide(restoreWindow)
                .wide(restoreConnection)
                .wide(persistTransfers)
                .wide(bucketAdmin)
                .wide(new JLabel("<html><div style='width:420px;margin-top:6px'>Deletes always ask for "
                        + "confirmation.</div></html>"))
                .fillRemaining()));
        tabs.addTab("Transfers", new JScrollPane(new FormPanel()
                .row("Concurrent transfers:", concurrent)
                .row("Multipart threshold (MB):", threshold)
                .row("Multipart part size (MB):", partSize)
                .row("Parallel parts per file:", parallelParts)
                .row("Retry count:", retryCount)
                .row("Retry delay (s):", retryDelay)
                .row("Connect timeout (s):", connectTimeout)
                .row("Read timeout (s):", requestTimeout)
                .row("Bandwidth limit (KB/s, 0 = unlimited):", bandwidth)
                .wide(new JLabel("<html><div style='width:420px;margin-top:6px'>Timeout changes apply to "
                        + "connections opened after saving.</div></html>"))
                .fillRemaining()));
        tabs.addTab("S3", new JScrollPane(new FormPanel()
                .row("Default region:", defaultRegion)
                .row("Default endpoint:", defaultEndpoint)
                .wide(pathStyle)
                .row("Default connection:", defaultConnection)
                .fillRemaining()));
        tabs.addTab("Appearance", new JScrollPane(new FormPanel()
                .row("Theme:", theme)
                .row("Font size:", fontSize)
                .row("Table density:", density)
                .wide(previewPane)
                .fillRemaining()));

        JButton ok = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            store(ctx.settings.get());
            ctx.settings.commit();
            applied = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(ok, cancel), BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, ok);
        setSize(620, 560);
        setLocationRelativeTo(owner);
    }

    private record ConnectionChoice(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private void load(AppSettings s) {
        downloadDir.setText(s.general.defaultDownloadDir);
        doubleClick.setSelectedItem(s.general.doubleClickAction);
        conflictPolicy.setSelectedItem(s.general.downloadConflictPolicy);
        showHidden.setSelected(s.general.showHiddenObjects);
        restoreWindow.setSelected(s.general.restoreWindow);
        restoreConnection.setSelected(s.general.restoreConnection);
        confirmDrop.setSelected(s.general.confirmDragDropUpload);
        persistTransfers.setSelected(s.general.persistTransfers);
        bucketAdmin.setSelected(s.general.allowBucketAdmin);
        previewKb.setValue((int) Math.min(Integer.MAX_VALUE, s.general.previewMaxKb));

        concurrent.setValue(s.transfers.concurrentTransfers);
        threshold.setValue(s.transfers.multipartThresholdMb);
        partSize.setValue(s.transfers.partSizeMb);
        parallelParts.setValue(s.transfers.parallelParts);
        retryCount.setValue(s.transfers.retryCount);
        retryDelay.setValue(s.transfers.retryDelaySeconds);
        connectTimeout.setValue(s.transfers.connectTimeoutSeconds);
        requestTimeout.setValue(s.transfers.requestTimeoutSeconds);
        bandwidth.setValue(s.transfers.bandwidthLimitKbps);

        defaultRegion.setText(s.s3.defaultRegion);
        defaultEndpoint.setText(s.s3.defaultEndpoint);
        pathStyle.setSelected(s.s3.pathStyleAccess);
        ConnectionChoice none = new ConnectionChoice(null, "(none)");
        defaultConnection.addItem(none);
        for (ConnectionProfile p : ctx.connections.profiles()) {
            ConnectionChoice choice = new ConnectionChoice(p.id, p.name);
            defaultConnection.addItem(choice);
            if (p.id.equals(s.s3.defaultConnectionId)) {
                defaultConnection.setSelectedItem(choice);
            }
        }

        theme.setSelectedItem(s.appearance.theme);
        fontSize.setValue(s.appearance.fontSize);
        density.setSelectedItem(s.appearance.tableDensity);
        previewPane.setSelected(s.appearance.previewPaneVisible);
    }

    private void store(AppSettings s) {
        s.general.defaultDownloadDir = downloadDir.getText().trim();
        s.general.doubleClickAction = (AppSettings.DoubleClickAction) doubleClick.getSelectedItem();
        s.general.downloadConflictPolicy = (ConflictPolicy) conflictPolicy.getSelectedItem();
        s.general.showHiddenObjects = showHidden.isSelected();
        s.general.restoreWindow = restoreWindow.isSelected();
        s.general.restoreConnection = restoreConnection.isSelected();
        s.general.confirmDragDropUpload = confirmDrop.isSelected();
        s.general.persistTransfers = persistTransfers.isSelected();
        s.general.allowBucketAdmin = bucketAdmin.isSelected();
        s.general.previewMaxKb = ((Number) previewKb.getValue()).longValue();

        s.transfers.concurrentTransfers = (Integer) concurrent.getValue();
        s.transfers.multipartThresholdMb = (Integer) threshold.getValue();
        s.transfers.partSizeMb = (Integer) partSize.getValue();
        s.transfers.parallelParts = (Integer) parallelParts.getValue();
        s.transfers.retryCount = (Integer) retryCount.getValue();
        s.transfers.retryDelaySeconds = (Integer) retryDelay.getValue();
        s.transfers.connectTimeoutSeconds = (Integer) connectTimeout.getValue();
        s.transfers.requestTimeoutSeconds = (Integer) requestTimeout.getValue();
        s.transfers.bandwidthLimitKbps = ((Number) bandwidth.getValue()).longValue();

        s.s3.defaultRegion = defaultRegion.getText().trim();
        s.s3.defaultEndpoint = defaultEndpoint.getText().trim();
        s.s3.pathStyleAccess = pathStyle.isSelected();
        Object choice = defaultConnection.getSelectedItem();
        s.s3.defaultConnectionId = choice instanceof ConnectionChoice c ? c.id() : null;

        s.appearance.theme = (AppSettings.Theme) theme.getSelectedItem();
        s.appearance.fontSize = (Integer) fontSize.getValue();
        s.appearance.tableDensity = (AppSettings.TableDensity) density.getSelectedItem();
        s.appearance.previewPaneVisible = previewPane.isSelected();
    }
}
