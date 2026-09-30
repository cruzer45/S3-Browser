package com.s3explorer.ui;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AppPaths;
import com.s3explorer.config.AppSettings;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.controller.BrowserController;
import com.s3explorer.controller.ConnectionController;
import com.s3explorer.controller.ObjectController;
import com.s3explorer.controller.TransferController;
import com.s3explorer.model.S3BucketInfo;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import com.s3explorer.service.ConnectionService;
import com.s3explorer.service.TransferService;
import com.s3explorer.ui.browser.BucketTreePanel;
import com.s3explorer.ui.browser.LocalFilePanel;
import com.s3explorer.ui.browser.ObjectTablePanel;
import com.s3explorer.ui.browser.PathNavigationPanel;
import com.s3explorer.ui.browser.PreviewPanel;
import com.s3explorer.ui.browser.S3TransferHandler;
import com.s3explorer.ui.dialog.AboutDialog;
import com.s3explorer.ui.dialog.SettingsDialog;
import com.s3explorer.ui.transfer.TransferQueuePanel;
import com.s3explorer.util.ErrorTranslator;
import com.s3explorer.util.MimeTypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;

/** The application window. */
public class MainWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final AppContext ctx;
    private final BrowserController browser;
    private final ConnectionController connectionController;
    private final TransferController transferController;
    private final ObjectController objectController;

    private final ObjectTablePanel objectTable = new ObjectTablePanel();
    private final PathNavigationPanel pathBar = new PathNavigationPanel();
    private final BucketTreePanel bucketTree;
    private final PreviewPanel previewPanel = new PreviewPanel();
    private final TransferQueuePanel transferPanel;
    private final StatusBar statusBar = new StatusBar();
    private LocalFilePanel localPanel;

    private final JComboBox<ConnectionProfile> connectionCombo = new JComboBox<>();
    private final JLabel regionLabel = new JLabel();
    private boolean updatingCombo;
    private final JPanel centerHolder = new JPanel(new BorderLayout());
    private JSplitPane treeSplit;
    private JSplitPane transferSplit;
    private JSplitPane localSplit;
    private JSplitPane previewSplit;
    private final Timer previewTimer;

    // Actions
    private Action connectionsAction, disconnectAction, uploadFilesAction, uploadFolderAction, downloadAction,
            newFolderAction, exitAction, copyAction, cutAction, pasteAction, copyToAction, moveToAction,
            renameAction, deleteAction, selectAllAction, copyUriAction, copyUrlAction, presignAction,
            propertiesAction, backAction, forwardAction, upAction, refreshAction, editPathAction, previewAction,
            searchAction, createBucketAction, deleteBucketAction, settingsAction, logsAction, shortcutsAction,
            aboutAction, openAction;
    private final JCheckBoxMenuItem dualPaneItem = new JCheckBoxMenuItem("Dual Pane (Local | S3)");
    private final JCheckBoxMenuItem previewPaneItem = new JCheckBoxMenuItem("Preview Pane");
    private final JCheckBoxMenuItem transfersItem = new JCheckBoxMenuItem("Transfer Queue");
    private final JCheckBoxMenuItem hiddenItem = new JCheckBoxMenuItem("Show Hidden Objects");
    private final JToggleButton dualPaneButton = new JToggleButton(Icons.get("dual-pane"));
    private final JToggleButton previewButton = new JToggleButton(Icons.get("preview"));

    public MainWindow(AppContext ctx) {
        super(AppContext.APP_NAME);
        this.ctx = ctx;
        this.browser = new BrowserController(ctx);
        this.bucketTree = new BucketTreePanel(ctx);
        this.transferPanel = new TransferQueuePanel(ctx.transfers, ctx.connections);
        this.connectionController = new ConnectionController(ctx, this, browser);
        this.transferController = new TransferController(ctx, this, browser,
                () -> localPanel != null && ctx.settings.get().appearance.dualPane ? localPanel.currentDirectory() : null);
        this.objectController = new ObjectController(ctx, this, browser, transferController);
        this.previewTimer = new Timer(300, e -> updatePreview());
        this.previewTimer.setRepeats(false);

        setIconImages(Icons.appImages());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        createActions();
        setJMenuBar(createMenuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.add(createToolBar(), BorderLayout.NORTH);
        root.add(centerHolder, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        wireBrowser();
        wireTransfers();
        wireConnections();
        rebuildLayout();
        updateActions();
        restoreBounds();
    }

    public void showWindow() {
        setVisible(true);
        List<TransferTask> restored = ctx.transfers.loadPersisted();
        if (!restored.isEmpty()) {
            ctx.transfers.restore(restored);
            log.info("Restored {} unfinished transfer(s)", restored.size());
        }
        SwingUtilities.invokeLater(connectionController::restoreOnStartup);
    }

    // --- actions -------------------------------------------------------------------------

    private Action action(String name, String icon, KeyStroke key, Runnable handler) {
        Action a = new AbstractAction(name, icon == null ? null : Icons.get(icon)) {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    handler.run();
                } catch (RuntimeException ex) {
                    log.error("Action '{}' failed", name, ex);
                    Dialogs.error(MainWindow.this, ex, null);
                }
            }
        };
        if (key != null) {
            a.putValue(Action.ACCELERATOR_KEY, key);
        }
        a.putValue(Action.SHORT_DESCRIPTION, name.replace("…", "") + (key == null ? "" : " (" + keyText(key) + ")"));
        return a;
    }

    private static String keyText(KeyStroke key) {
        String mods = InputEvent.getModifiersExText(key.getModifiers());
        String k = KeyEvent.getKeyText(key.getKeyCode());
        return mods.isEmpty() ? k : mods + "+" + k;
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private List<S3ObjectItem> selection() {
        return objectTable.selectedItems().stream().filter(i -> !i.isParent()).toList();
    }

    private S3ObjectItem single() {
        List<S3ObjectItem> sel = selection();
        return sel.size() == 1 ? sel.get(0) : null;
    }

    private void createActions() {
        connectionsAction = action("Connections…", "connection", key(KeyEvent.VK_K, MENU), connectionController::openManager);
        disconnectAction = action("Disconnect", null, null, connectionController::disconnect);
        uploadFilesAction = action("Upload Files…", "upload", key(KeyEvent.VK_U, MENU),
                transferController::chooseAndUploadFiles);
        uploadFolderAction = action("Upload Folder…", "upload-folder", key(KeyEvent.VK_U, MENU | InputEvent.SHIFT_DOWN_MASK),
                transferController::chooseAndUploadFolder);
        downloadAction = action("Download…", "download", key(KeyEvent.VK_D, MENU),
                () -> transferController.download(selection()));
        newFolderAction = action("New Folder…", "new-folder", key(KeyEvent.VK_N, MENU | InputEvent.SHIFT_DOWN_MASK),
                transferController::newFolder);
        exitAction = action("Exit", null, key(KeyEvent.VK_Q, MENU), this::exit);

        copyAction = action("Copy", "copy", key(KeyEvent.VK_C, MENU), () -> {
            browser.copyToClipboard(selection(), false);
            updateActions();
        });
        cutAction = action("Cut", "cut", key(KeyEvent.VK_X, MENU), () -> {
            browser.copyToClipboard(selection(), true);
            updateActions();
        });
        pasteAction = action("Paste", "paste", key(KeyEvent.VK_V, MENU), transferController::paste);
        copyToAction = action("Copy To…", null, null, () -> transferController.copyOrMoveTo(selection(), false));
        moveToAction = action("Move To…", null, null, () -> transferController.copyOrMoveTo(selection(), true));
        renameAction = action("Rename…", null, key(KeyEvent.VK_F2, 0), () -> transferController.rename(single()));
        deleteAction = action("Delete…", "delete", key(KeyEvent.VK_DELETE, 0), () -> transferController.delete(selection()));
        selectAllAction = action("Select All", null, key(KeyEvent.VK_A, MENU), () -> objectTable.table().selectAll());
        copyUriAction = action("Copy S3 URI", "link", key(KeyEvent.VK_C, MENU | InputEvent.SHIFT_DOWN_MASK),
                () -> objectController.copyS3Uris(selection()));
        copyUrlAction = action("Copy Object URL", null, null, () -> objectController.copyObjectUrls(selection()));
        presignAction = action("Generate Presigned URL…", "key", key(KeyEvent.VK_P, MENU | InputEvent.SHIFT_DOWN_MASK),
                () -> objectController.presign(single()));
        propertiesAction = action("Properties", "properties", key(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK),
                () -> objectController.properties(single()));

        backAction = action("Back", "back", key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), browser::back);
        forwardAction = action("Forward", "forward", key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), browser::forward);
        upAction = action("Up", "up", key(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), browser::up);
        refreshAction = action("Refresh", "refresh", key(KeyEvent.VK_F5, 0), browser::refresh);
        editPathAction = action("Edit Path", null, key(KeyEvent.VK_L, MENU), pathBar::startEditing);
        previewAction = action("Preview", "preview", null, () -> objectController.preview(single()));
        openAction = action("Open", null, null, () -> objectController.activate(single()));

        searchAction = action("Search…", "search", key(KeyEvent.VK_F, MENU), objectController::openSearch);
        createBucketAction = action("Create Bucket…", "bucket", null, transferController::createBucket);
        deleteBucketAction = action("Delete Bucket…", null, null, () -> transferController.deleteBucket(single()));
        settingsAction = action("Settings…", "settings", key(KeyEvent.VK_COMMA, MENU), this::openSettings);
        logsAction = action("Open Log Folder", null, null, this::openLogs);
        shortcutsAction = action("Keyboard Shortcuts", null, null, () -> AboutDialog.showShortcuts(this));
        aboutAction = action("About " + AppContext.APP_NAME, null, null, () -> AboutDialog.showAbout(this));
    }

    private void updateActions() {
        boolean connected = browser.connection() != null;
        boolean inBucket = connected && browser.location() != null;
        List<S3ObjectItem> sel = selection();
        S3ObjectItem one = sel.size() == 1 ? sel.get(0) : null;
        boolean anyObject = sel.stream().anyMatch(i -> i.isFile() || i.kind() == S3ObjectItem.Kind.FOLDER);
        boolean allowBuckets = ctx.settings.get().general.allowBucketAdmin;

        disconnectAction.setEnabled(connected);
        uploadFilesAction.setEnabled(inBucket);
        uploadFolderAction.setEnabled(inBucket);
        downloadAction.setEnabled(connected && !sel.isEmpty());
        newFolderAction.setEnabled(inBucket);
        copyAction.setEnabled(anyObject);
        cutAction.setEnabled(anyObject);
        pasteAction.setEnabled(inBucket && browser.clipboard() != null);
        copyToAction.setEnabled(anyObject);
        moveToAction.setEnabled(anyObject);
        renameAction.setEnabled(one != null && (one.isFile() || one.kind() == S3ObjectItem.Kind.FOLDER));
        deleteAction.setEnabled(anyObject);
        selectAllAction.setEnabled(connected);
        copyUriAction.setEnabled(!sel.isEmpty());
        copyUrlAction.setEnabled(!sel.isEmpty());
        presignAction.setEnabled(one != null && one.isFile());
        propertiesAction.setEnabled(one != null);
        previewAction.setEnabled(one != null && one.isFile() && MimeTypeUtils.isPreviewable(one.name()));
        openAction.setEnabled(one != null);
        backAction.setEnabled(browser.canBack());
        forwardAction.setEnabled(browser.canForward());
        upAction.setEnabled(browser.canUp());
        refreshAction.setEnabled(connected);
        editPathAction.setEnabled(connected);
        searchAction.setEnabled(inBucket);
        createBucketAction.setEnabled(connected && allowBuckets);
        deleteBucketAction.setEnabled(allowBuckets && one != null && one.kind() == S3ObjectItem.Kind.BUCKET);
    }

    // --- menus and toolbar -----------------------------------------------------------------

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = menu("File", KeyEvent.VK_F);
        file.add(connectionsAction);
        file.add(disconnectAction);
        file.addSeparator();
        file.add(uploadFilesAction);
        file.add(uploadFolderAction);
        file.add(downloadAction);
        file.addSeparator();
        file.add(newFolderAction);
        file.addSeparator();
        file.add(exitAction);

        JMenu edit = menu("Edit", KeyEvent.VK_E);
        edit.add(copyAction);
        edit.add(cutAction);
        edit.add(pasteAction);
        edit.add(copyToAction);
        edit.add(moveToAction);
        edit.addSeparator();
        edit.add(renameAction);
        edit.add(deleteAction);
        edit.addSeparator();
        edit.add(selectAllAction);
        edit.addSeparator();
        edit.add(copyUriAction);
        edit.add(copyUrlAction);
        edit.add(presignAction);
        edit.addSeparator();
        edit.add(propertiesAction);

        JMenu view = menu("View", KeyEvent.VK_V);
        view.add(backAction);
        view.add(forwardAction);
        view.add(upAction);
        view.add(refreshAction);
        view.add(editPathAction);
        view.addSeparator();
        view.add(previewAction);
        view.addSeparator();
        AppSettings.Appearance appearance = ctx.settings.get().appearance;
        dualPaneItem.setSelected(appearance.dualPane);
        previewPaneItem.setSelected(appearance.previewPaneVisible);
        transfersItem.setSelected(appearance.transfersVisible);
        hiddenItem.setSelected(ctx.settings.get().general.showHiddenObjects);
        dualPaneItem.setAccelerator(key(KeyEvent.VK_D, MENU | InputEvent.SHIFT_DOWN_MASK));
        dualPaneItem.addActionListener(e -> setDualPane(dualPaneItem.isSelected()));
        previewPaneItem.addActionListener(e -> setPreviewPane(previewPaneItem.isSelected()));
        transfersItem.addActionListener(e -> {
            appearance.transfersVisible = transfersItem.isSelected();
            ctx.settings.save();
            rebuildLayout();
        });
        hiddenItem.addActionListener(e -> {
            ctx.settings.get().general.showHiddenObjects = hiddenItem.isSelected();
            ctx.settings.save();
            browser.refresh();
        });
        view.add(dualPaneItem);
        view.add(previewPaneItem);
        view.add(transfersItem);
        view.add(hiddenItem);
        view.addSeparator();
        JMenu theme = new JMenu("Theme");
        ButtonGroup group = new ButtonGroup();
        for (AppSettings.Theme t : AppSettings.Theme.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(t.toString(), t == appearance.theme);
            item.addActionListener(e -> {
                appearance.theme = t;
                ctx.settings.save();
                ThemeManager.apply(appearance);
            });
            group.add(item);
            theme.add(item);
        }
        view.add(theme);

        JMenu tools = menu("Tools", KeyEvent.VK_T);
        tools.add(searchAction);
        tools.addSeparator();
        tools.add(createBucketAction);
        tools.add(deleteBucketAction);
        tools.addSeparator();
        tools.add(settingsAction);
        tools.add(logsAction);

        JMenu help = menu("Help", KeyEvent.VK_H);
        help.add(shortcutsAction);
        help.add(aboutAction);

        bar.add(file);
        bar.add(edit);
        bar.add(view);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    private static JMenu menu(String name, int mnemonic) {
        JMenu m = new JMenu(name);
        m.setMnemonic(mnemonic);
        return m;
    }

    private JComponent createToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        for (Action a : new Action[]{backAction, forwardAction, upAction, refreshAction}) {
            bar.add(toolButton(a));
        }
        bar.addSeparator();
        for (Action a : new Action[]{uploadFilesAction, uploadFolderAction, downloadAction, newFolderAction, deleteAction}) {
            bar.add(toolButton(a));
        }
        bar.addSeparator();
        for (Action a : new Action[]{searchAction, presignAction, propertiesAction}) {
            bar.add(toolButton(a));
        }
        bar.addSeparator();
        dualPaneButton.setToolTipText("Dual pane: local files alongside S3");
        dualPaneButton.setSelected(ctx.settings.get().appearance.dualPane);
        dualPaneButton.addActionListener(e -> setDualPane(dualPaneButton.isSelected()));
        previewButton.setToolTipText("Preview pane");
        previewButton.setSelected(ctx.settings.get().appearance.previewPaneVisible);
        previewButton.addActionListener(e -> setPreviewPane(previewButton.isSelected()));
        bar.add(dualPaneButton);
        bar.add(previewButton);

        bar.add(javax.swing.Box.createHorizontalGlue());
        JLabel label = new JLabel("Connection:");
        label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 6));
        bar.add(label);
        connectionCombo.setPrototypeDisplayValue(prototypeProfile());
        connectionCombo.setMaximumSize(new Dimension(260, 30));
        connectionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value == null ? "Select a connection…" : value, index,
                        selected, focus);
                setIcon(value == null ? null : Icons.get("connection"));
                return this;
            }
        });
        connectionCombo.addActionListener(e -> {
            if (!updatingCombo && connectionCombo.getSelectedItem() instanceof ConnectionProfile p) {
                S3Connection active = ctx.connections.active();
                if (active == null || !active.id().equals(p.id)) {
                    connectionController.connect(p.id);
                }
            }
        });
        bar.add(connectionCombo);
        bar.add(toolButton(connectionsAction));
        regionLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
        regionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        bar.add(regionLabel);
        bar.add(toolButton(settingsAction));
        reloadConnectionCombo();
        return bar;
    }

    private static ConnectionProfile prototypeProfile() {
        ConnectionProfile p = new ConnectionProfile();
        p.name = "Production AWS account";
        return p;
    }

    private static JButton toolButton(Action action) {
        JButton b = new JButton(action);
        b.setHideActionText(true);
        b.setFocusable(false);
        return b;
    }

    private JPopupMenu popupFor(List<S3ObjectItem> items) {
        JPopupMenu menu = new JPopupMenu();
        List<S3ObjectItem> sel = items.stream().filter(i -> !i.isParent()).toList();
        S3ObjectItem one = sel.size() == 1 ? sel.get(0) : null;
        if (sel.isEmpty()) {
            if (browser.location() != null) {
                menu.add(uploadFilesAction);
                menu.add(uploadFolderAction);
                menu.add(newFolderAction);
                menu.add(pasteAction);
                menu.addSeparator();
            } else if (ctx.settings.get().general.allowBucketAdmin) {
                menu.add(createBucketAction);
                menu.addSeparator();
            }
            menu.add(refreshAction);
            return menu;
        }
        if (one != null && one.kind() == S3ObjectItem.Kind.BUCKET) {
            menu.add(openAction);
            menu.add(downloadAction);
            menu.addSeparator();
            menu.add(copyUriAction);
            if (ctx.settings.get().general.allowBucketAdmin) {
                menu.addSeparator();
                menu.add(deleteBucketAction);
            }
            menu.addSeparator();
            menu.add(propertiesAction);
            return menu;
        }
        boolean folder = one != null && one.kind() == S3ObjectItem.Kind.FOLDER;
        if (one != null) {
            menu.add(openAction);
        }
        if (one != null && one.isFile()) {
            menu.add(previewAction);
        }
        menu.add(downloadAction).setText(folder ? "Download Folder…" : "Download…");
        menu.add(renameAction);
        menu.add(copyAction);
        menu.add(cutAction);
        menu.add(copyToAction);
        menu.add(moveToAction);
        menu.add(deleteAction);
        menu.addSeparator();
        menu.add(copyUriAction);
        menu.add(copyUrlAction);
        if (one != null && one.isFile()) {
            menu.add(presignAction);
        }
        menu.addSeparator();
        menu.add(propertiesAction);
        return menu;
    }

    // --- wiring ------------------------------------------------------------------------------

    private void wireBrowser() {
        objectTable.onActivate(objectController::activate);
        objectTable.onSelection(items -> {
            updateActions();
            List<S3ObjectItem> sel = items.stream().filter(i -> !i.isParent()).toList();
            long bytes = sel.stream().filter(S3ObjectItem::isFile).mapToLong(S3ObjectItem::size).sum();
            statusBar.setSelection(sel.size(), bytes);
            previewTimer.restart();
        });
        objectTable.setPopupFactory(this::popupFor);
        objectTable.bindClipboard(copyAction, cutAction, pasteAction);
        objectTable.bindKey(key(KeyEvent.VK_ENTER, 0), "s3open", openAction);
        objectTable.bindKey(key(KeyEvent.VK_F2, 0), "s3rename", renameAction);
        objectTable.bindKey(key(KeyEvent.VK_BACK_SPACE, 0), "s3up", upAction);
        objectTable.bindKey(key(KeyEvent.VK_SPACE, 0), "s3preview", previewAction);
        objectTable.table().setTransferHandler(new S3TransferHandler(
                () -> browser.connection() == null ? null : browser.connection().id(),
                objectTable::selectedItems,
                support -> {
                    S3ObjectItem target = objectTable.containerAtDrop(support);
                    if (target != null) {
                        return target.path();
                    }
                    return browser.location();
                },
                transferController));

        pathBar.onNavigate(browser::navigate);
        pathBar.onFilter(objectTable::setFilter);
        getRootPane().registerKeyboardAction(e -> pathBar.startEditing(), key(KeyEvent.VK_L, MENU),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        bucketTree.onNavigate(browser::navigate);
        bucketTree.tree().setTransferHandler(new S3TransferHandler(
                () -> browser.connection() == null ? null : browser.connection().id(),
                null, bucketTree::dropFolder, transferController));

        browser.addListener(new BrowserController.Listener() {
            @Override
            public void connectionChanged(S3Connection connection) {
                bucketTree.setConnection(connection);
                previewPanel.showMessage("Select a file to preview");
                statusBar.setConnection(connection == null ? StatusBar.ConnectionState.DISCONNECTED
                        : StatusBar.ConnectionState.CONNECTING, connection == null ? null : connection.profile().name);
                regionLabel.setText(connection == null ? "" : "Region: " + connection.region().id());
                selectComboProfile(connection == null ? null : connection.id());
                updateTitle();
            }

            @Override
            public void locationChanged(S3Path location) {
                objectTable.clearSelection();
                pathBar.setLocation(browser.connection() != null, location);
                pathBar.clearFilter();
                bucketTree.reveal(location);
                statusBar.setBucket(location == null ? null : location.bucket());
                updateActions();
                updateTitle();
            }

            @Override
            public void itemsChanged(List<S3ObjectItem> items, boolean loading) {
                String empty = browser.connection() == null ? "Not connected. Choose a connection to begin."
                        : loading ? "Loading…"
                        : browser.location() == null ? "No buckets" : "This folder is empty. Drop files here to upload.";
                objectTable.setItems(items, empty);
                int count = (int) items.stream().filter(i -> !i.isParent()).count();
                statusBar.setObjects(count, loading);
                if (browser.isConnected() && browser.connection() != null) {
                    statusBar.setConnection(StatusBar.ConnectionState.CONNECTED, browser.connection().profile().name);
                }
                updateActions();
            }

            @Override
            public void loadFailed(Throwable error) {
                ErrorTranslator.UserMessage m = ErrorTranslator.translate(error,
                        browser.location() == null ? "list buckets" : "list objects in " + browser.location().bucket());
                objectTable.setEmptyText(m.title());
                if (!browser.isConnected() && browser.connection() != null) {
                    statusBar.setConnection(StatusBar.ConnectionState.ERROR, browser.connection().profile().name);
                }
                Dialogs.error(MainWindow.this, error,
                        browser.location() == null ? "list buckets" : "list objects in " + browser.location().bucket());
            }

            @Override
            public void bucketsLoaded(List<S3BucketInfo> buckets) {
                bucketTree.setBuckets(buckets);
            }
        });
    }

    private void wireTransfers() {
        transferPanel.onActiveCountChanged(statusBar::setTransfers);
        Timer localRefresh = new Timer(800, e -> {
            if (localPanel != null) {
                localPanel.refresh();
            }
        });
        localRefresh.setRepeats(false);
        ctx.transfers.addListener(new TransferService.Listener() {
            @Override
            public void tasksAdded(List<TransferTask> tasks) {
                SwingUtilities.invokeLater(() -> {
                    if (!ctx.settings.get().appearance.transfersVisible) {
                        transfersItem.setSelected(true);
                        ctx.settings.get().appearance.transfersVisible = true;
                        rebuildLayout();
                    }
                });
            }

            @Override
            public void tasksRemoved(List<TransferTask> tasks) {
            }

            @Override
            public void taskChanged(TransferTask task) {
                if (task.type() == TransferType.DOWNLOAD && task.status() == TransferStatus.COMPLETED) {
                    SwingUtilities.invokeLater(() -> {
                        if (localPanel != null && localPanel.currentDirectory() != null && task.localPath() != null
                                && task.localPath().startsWith(localPanel.currentDirectory())) {
                            localRefresh.restart();
                        }
                    });
                }
            }
        });
    }

    private void wireConnections() {
        ctx.connections.addListener(new ConnectionService.Listener() {
            @Override
            public void profilesChanged() {
                SwingUtilities.invokeLater(MainWindow.this::reloadConnectionCombo);
            }
        });
        ctx.settings.addListener(settings -> {
            ThemeManager.apply(settings.appearance);
            ctx.transfers.applySettings();
            hiddenItem.setSelected(settings.general.showHiddenObjects);
            setPreviewPane(settings.appearance.previewPaneVisible);
            updateActions();
            browser.refresh();
        });
    }

    private void reloadConnectionCombo() {
        updatingCombo = true;
        try {
            connectionCombo.removeAllItems();
            for (ConnectionProfile p : ctx.connections.profiles()) {
                connectionCombo.addItem(p);
            }
            S3Connection active = ctx.connections.active();
            selectComboProfile(active == null ? null : active.id());
        } finally {
            updatingCombo = false;
        }
    }

    private void selectComboProfile(String id) {
        updatingCombo = true;
        try {
            connectionCombo.setSelectedItem(null);
            for (int i = 0; i < connectionCombo.getItemCount(); i++) {
                if (connectionCombo.getItemAt(i).id.equals(id)) {
                    connectionCombo.setSelectedIndex(i);
                }
            }
        } finally {
            updatingCombo = false;
        }
    }

    private void updateTitle() {
        S3Connection conn = browser.connection();
        S3Path location = browser.location();
        String suffix = conn == null ? null
                : location == null ? conn.profile().name : location.toUri() + " (" + conn.profile().name + ")";
        setTitle(Dialogs.appTitle(suffix));
    }

    private void updatePreview() {
        if (!ctx.settings.get().appearance.previewPaneVisible) {
            return;
        }
        S3ObjectItem one = single();
        S3Connection conn = browser.connection();
        if (one == null || !one.isFile() || conn == null) {
            previewPanel.showMessage(one == null ? "Select a file to preview" : "");
        } else {
            previewPanel.load(ctx, conn, one.path());
        }
    }

    // --- layout --------------------------------------------------------------------------

    private void setDualPane(boolean on) {
        ctx.settings.get().appearance.dualPane = on;
        ctx.settings.save();
        dualPaneItem.setSelected(on);
        dualPaneButton.setSelected(on);
        rebuildLayout();
    }

    private void setPreviewPane(boolean on) {
        ctx.settings.get().appearance.previewPaneVisible = on;
        ctx.settings.save();
        previewPaneItem.setSelected(on);
        previewButton.setSelected(on);
        rebuildLayout();
        if (on) {
            updatePreview();
        }
    }

    private void rememberDividers() {
        AppSettings.WindowState w = ctx.settings.get().window;
        if (treeSplit != null) w.treeDivider = treeSplit.getDividerLocation();
        if (transferSplit != null && transferSplit.getHeight() > 0) {
            w.transferDivider = transferSplit.getHeight() - transferSplit.getDividerLocation();
        }
        if (localSplit != null) w.localDivider = localSplit.getDividerLocation();
        if (previewSplit != null && previewSplit.getWidth() > 0) {
            w.previewDivider = previewSplit.getWidth() - previewSplit.getDividerLocation();
        }
        if (localPanel != null && localPanel.currentDirectory() != null) {
            w.lastLocalDir = localPanel.currentDirectory().toString();
        }
    }

    private void rebuildLayout() {
        rememberDividers();
        AppSettings.Appearance a = ctx.settings.get().appearance;
        AppSettings.WindowState w = ctx.settings.get().window;

        Component objects = objectTable;
        previewSplit = null;
        if (a.previewPaneVisible) {
            previewSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectTable, previewPanel);
            previewSplit.setResizeWeight(1.0);
            previewSplit.setBorder(BorderFactory.createEmptyBorder());
            int paneWidth = w.previewDivider > 80 ? w.previewDivider : 340;
            SwingUtilities.invokeLater(() -> {
                if (previewSplit != null) {
                    int width = previewSplit.getWidth();
                    previewSplit.setDividerLocation(Math.max(200, width - Math.min(paneWidth, width / 2)));
                }
            });
            objects = previewSplit;
        }
        JPanel browserArea = new JPanel(new BorderLayout());
        browserArea.add(pathBar, BorderLayout.NORTH);
        browserArea.add(objects, BorderLayout.CENTER);

        treeSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, bucketTree, browserArea);
        treeSplit.setDividerLocation(w.treeDivider > 0 ? w.treeDivider : 240);
        treeSplit.setBorder(BorderFactory.createEmptyBorder());
        Component main = treeSplit;

        localSplit = null;
        if (a.dualPane) {
            if (localPanel == null) {
                localPanel = new LocalFilePanel(w.lastLocalDir == null ? null : Path.of(w.lastLocalDir));
                localPanel.onUpload(paths -> {
                    if (!paths.isEmpty()) {
                        transferController.uploadPaths(paths, browser.location(), false);
                    }
                });
                localPanel.onS3Drop((sel, dir) -> transferController.downloadTo(sel.connectionId(), sel.items(), dir, null));
            }
            localSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPanel, treeSplit);
            localSplit.setDividerLocation(w.localDivider > 0 ? w.localDivider : 420);
            localSplit.setBorder(BorderFactory.createEmptyBorder());
            main = localSplit;
        }

        transferSplit = null;
        if (a.transfersVisible) {
            transferSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, main, transferPanel);
            transferSplit.setResizeWeight(1.0);
            transferSplit.setBorder(BorderFactory.createEmptyBorder());
            int paneHeight = w.transferDivider > 60 ? w.transferDivider : 210;
            SwingUtilities.invokeLater(() -> {
                if (transferSplit != null) {
                    int height = transferSplit.getHeight();
                    transferSplit.setDividerLocation(Math.max(150, height - Math.min(paneHeight, height * 2 / 3)));
                }
            });
            main = transferSplit;
        }
        centerHolder.removeAll();
        centerHolder.add(main, BorderLayout.CENTER);
        centerHolder.revalidate();
        centerHolder.repaint();
    }

    private void restoreBounds() {
        AppSettings.WindowState w = ctx.settings.get().window;
        setMinimumSize(new Dimension(760, 480));
        setSize(Math.max(760, w.width), Math.max(480, w.height));
        boolean placed = false;
        if (ctx.settings.get().general.restoreWindow && w.x >= 0 && w.y >= 0) {
            Rectangle bounds = new Rectangle(w.x, w.y, getWidth(), getHeight());
            Rectangle screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (screens.intersects(bounds)) {
                setLocation(w.x, w.y);
                placed = true;
            }
        }
        if (!placed) {
            setLocationRelativeTo(null);
        }
        if (ctx.settings.get().general.restoreWindow && w.maximized) {
            setExtendedState(MAXIMIZED_BOTH);
        }
    }

    private void saveBounds() {
        AppSettings.WindowState w = ctx.settings.get().window;
        rememberDividers();
        w.maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        if (!w.maximized) {
            w.x = getX();
            w.y = getY();
            w.width = getWidth();
            w.height = getHeight();
        }
    }

    // --- misc ----------------------------------------------------------------------------

    private void openSettings() {
        SettingsDialog.show(this, ctx);
    }

    private void openLogs() {
        try {
            Desktop.getDesktop().open(AppPaths.logsDir().toFile());
        } catch (Exception e) {
            Dialogs.info(this, "Log folder", "Logs are stored in " + AppPaths.logsDir());
        }
    }

    private void exit() {
        long pending = ctx.transfers.pendingCount();
        if (pending > 0) {
            boolean persist = ctx.settings.get().general.persistTransfers;
            String message = pending + " transfer(s) are still queued or running. "
                    + (persist ? "They will be paused and can be resumed next time." : "They will be stopped.");
            int choice = JOptionPane.showConfirmDialog(this, message, "Exit " + AppContext.APP_NAME,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        saveBounds();
        ctx.settings.save();
        setVisible(false);
        ctx.transfers.shutdown(ctx.settings.get().general.persistTransfers);
        ctx.connections.closeAll();
        log.info("Exiting");
        dispose();
        System.exit(0);
    }
}
