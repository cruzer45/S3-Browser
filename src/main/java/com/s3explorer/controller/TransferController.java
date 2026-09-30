package com.s3explorer.controller;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import com.s3explorer.service.TransferPlanner;
import com.s3explorer.service.TransferService;
import com.s3explorer.ui.BusyDialog;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.dialog.CopyMoveDialog;
import com.s3explorer.ui.dialog.DeleteConfirmationDialog;
import com.s3explorer.ui.dialog.DownloadDialog;
import com.s3explorer.ui.dialog.UploadConfirmationDialog;
import com.s3explorer.util.Background;
import com.s3explorer.util.NameConflicts;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** File operations: upload, download, delete, rename, new folder, copy/move and paste. */
public class TransferController {

    private final AppContext ctx;
    private final JFrame frame;
    private final BrowserController browser;
    private final Supplier<Path> localDirectory;
    private final Timer refreshTimer;

    private enum ConflictChoice { REPLACE, SKIP, KEEP_BOTH }

    /**
     * @param localDirectory the local panel's directory in dual-pane mode, or null otherwise;
     *                       used as the default download location
     */
    public TransferController(AppContext ctx, JFrame frame, BrowserController browser, Supplier<Path> localDirectory) {
        this.ctx = ctx;
        this.frame = frame;
        this.browser = browser;
        this.localDirectory = localDirectory;
        this.refreshTimer = new Timer(700, e -> browser.refresh());
        this.refreshTimer.setRepeats(false);
        ctx.transfers.addListener(new TransferService.Listener() {
            @Override
            public void tasksAdded(List<TransferTask> tasks) {
            }

            @Override
            public void tasksRemoved(List<TransferTask> tasks) {
            }

            @Override
            public void taskChanged(TransferTask task) {
                if (task.status() == TransferStatus.COMPLETED && task.type() != TransferType.DOWNLOAD) {
                    SwingUtilities.invokeLater(() -> refreshIfAffected(task));
                }
            }
        });
    }

    /** Coalesces refreshes when transfers that touch the visible folder complete. */
    private void refreshIfAffected(TransferTask task) {
        S3Connection conn = browser.connection();
        S3Path location = browser.location();
        if (conn == null || location == null) {
            return;
        }
        boolean affected = false;
        if (task.destination() != null && conn.id().equals(task.destConnectionId())) {
            affected = isInside(task.destination(), location);
        }
        if (task.source() != null && conn.id().equals(task.sourceConnectionId())
                && (task.type() == TransferType.MOVE || task.type() == TransferType.DELETE)) {
            affected |= task.type() == TransferType.DELETE
                    ? task.source().bucket().equals(location.bucket())
                    : isInside(task.source(), location);
        }
        if (affected) {
            refreshTimer.restart();
        }
    }

    /** True if {@code path} is shown in {@code folder} (directly or inside a subfolder shown there). */
    private static boolean isInside(S3Path path, S3Path folder) {
        return path.bucket().equals(folder.bucket()) && path.key().startsWith(folder.key());
    }

    private S3Connection requireConnection() {
        S3Connection conn = browser.connection();
        if (conn == null) {
            Dialogs.info(frame, "Not connected", "Connect to a storage account first.");
        }
        return conn;
    }

    private S3Path requireFolder() {
        S3Path location = browser.location();
        if (location == null) {
            Dialogs.info(frame, "Open a bucket", "Open a bucket or folder first.");
        }
        return location;
    }

    // --- upload --------------------------------------------------------------------------

    public void chooseAndUploadFiles() {
        chooseAndUpload(JFileChooser.FILES_ONLY, "Upload Files");
    }

    public void chooseAndUploadFolder() {
        chooseAndUpload(JFileChooser.DIRECTORIES_ONLY, "Upload Folder");
    }

    private void chooseAndUpload(int mode, String title) {
        if (requireConnection() == null) {
            return;
        }
        S3Path folder = requireFolder();
        if (folder == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser(lastLocalDir());
        chooser.setDialogTitle(title + " to " + folder.toUri());
        chooser.setFileSelectionMode(mode);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showDialog(frame, "Upload") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        if (files.length == 0 && chooser.getSelectedFile() != null) {
            files = new File[]{chooser.getSelectedFile()};
        }
        List<Path> paths = Arrays.stream(files).map(File::toPath).toList();
        if (!paths.isEmpty()) {
            Path parent = paths.get(0).getParent();
            ctx.settings.get().window.lastLocalDir = parent == null ? null : parent.toString();
        }
        uploadPaths(paths, folder, false);
    }

    private File lastLocalDir() {
        String last = ctx.settings.get().window.lastLocalDir;
        return last == null ? null : new File(last);
    }

    /** Uploads local files/folders into {@code destination}, preserving folder structure. */
    public void uploadPaths(List<Path> paths, S3Path destination, boolean fromDragAndDrop) {
        S3Connection conn = requireConnection();
        if (conn == null || paths.isEmpty() || destination == null) {
            return;
        }
        String connectionId = conn.id();
        String connectionName = conn.profile().name;
        BusyDialog.run(frame, "Preparing upload", "Scanning local files", "upload files",
                (cancelled, progress) -> ctx.planner.planUpload(paths, connectionId, destination, cancelled, progress),
                plan -> {
                    if (plan.tasks().isEmpty() && plan.emptyFolders().isEmpty()) {
                        Dialogs.info(frame, "Nothing to upload", "The selection contains no files.");
                        return;
                    }
                    if (fromDragAndDrop && ctx.settings.get().general.confirmDragDropUpload
                            && !UploadConfirmationDialog.show(frame, plan, connectionName, ctx.settings)) {
                        return;
                    }
                    if (!plan.emptyFolders().isEmpty()) {
                        Background.run(() -> {
                            ctx.planner.createEmptyFolders(connectionId, plan.emptyFolders());
                            return null;
                        }, ignored -> browser.refreshIfShowing(connectionId, destination),
                                error -> Dialogs.error(frame, error, "create folders in " + destination.bucket()));
                    }
                    ctx.transfers.submit(plan.tasks());
                });
    }

    // --- download ------------------------------------------------------------------------

    public void download(List<S3ObjectItem> selection) {
        S3Connection conn = requireConnection();
        List<S3ObjectItem> items = selection.stream().filter(i -> !i.isParent()).toList();
        if (conn == null || items.isEmpty()) {
            return;
        }
        Path local = localDirectory == null ? null : localDirectory.get();
        String defaultDir = local != null ? local.toString() : ctx.settings.get().general.defaultDownloadDir;
        DownloadDialog.Result choice = DownloadDialog.show(frame, items, defaultDir,
                ctx.settings.get().general.downloadConflictPolicy);
        if (choice == null) {
            return;
        }
        if (choice.rememberAsDefault()) {
            ctx.settings.get().general.defaultDownloadDir = choice.directory().toString();
            ctx.settings.get().general.downloadConflictPolicy = choice.policy();
            ctx.settings.save();
        }
        downloadTo(conn.id(), items, choice.directory(), choice.policy());
    }

    /** Downloads without asking (used when dropping S3 items on the local panel). */
    public void downloadTo(String connectionId, List<S3ObjectItem> items, Path directory, ConflictPolicy policy) {
        BusyDialog.run(frame, "Preparing download", "Listing objects", "download objects",
                (cancelled, progress) -> ctx.planner.planDownload(connectionId, items, directory,
                        policy == null ? ctx.settings.get().general.downloadConflictPolicy : policy,
                        cancelled, progress),
                tasks -> {
                    if (tasks.isEmpty()) {
                        Dialogs.info(frame, "Nothing to download", "The selected folders contain no files.");
                    } else {
                        ctx.transfers.submit(tasks);
                    }
                });
    }

    // --- delete --------------------------------------------------------------------------

    public void delete(List<S3ObjectItem> selection) {
        S3Connection conn = requireConnection();
        S3Path location = browser.location();
        List<S3ObjectItem> items = selection.stream()
                .filter(i -> i.isFile() || i.kind() == S3ObjectItem.Kind.FOLDER).toList();
        if (conn == null || location == null || items.isEmpty()) {
            return;
        }
        String connectionId = conn.id();
        BusyDialog.run(frame, "Preparing delete", "Counting objects", "list objects in " + location.bucket(),
                (cancelled, progress) -> ctx.planner.planDelete(connectionId, location, items, cancelled, progress),
                plan -> {
                    if (plan.refs().isEmpty()) {
                        Dialogs.info(frame, "Nothing to delete", "The selected folders contain no objects.");
                        browser.refresh();
                        return;
                    }
                    if (!DeleteConfirmationDialog.show(frame, plan, items)) {
                        return;
                    }
                    String label = items.size() == 1 ? items.get(0).name() : items.size() + " items";
                    ctx.transfers.submit(List.of(TransferTask.delete(connectionId, location, plan.refs(), label)));
                });
    }

    // --- folders and rename ----------------------------------------------------------------

    public void newFolder() {
        S3Connection conn = requireConnection();
        S3Path location = conn == null ? null : requireFolder();
        if (location == null) {
            return;
        }
        String name = Dialogs.input(frame, "New Folder", "Folder name:", "New folder");
        if (name == null || name.isEmpty()) {
            return;
        }
        try {
            TransferPlanner.validateName(name);
        } catch (RuntimeException e) {
            Dialogs.error(frame, e, null);
            return;
        }
        S3Path folder = location.resolveFolder(name);
        Background.run(() -> {
            if (ctx.browser.exists(conn, folder)) {
                throw new com.s3explorer.util.UserFacingException("Folder exists.",
                        "A folder named \"" + name + "\" already exists here.");
            }
            ctx.browser.createFolder(conn, folder);
            return folder;
        }, created -> browser.refreshIfShowing(conn.id(), location),
                error -> Dialogs.error(frame, error, "create folders in " + location.bucket()));
    }

    public void rename(S3ObjectItem item) {
        S3Connection conn = requireConnection();
        if (conn == null || item == null || !(item.isFile() || item.kind() == S3ObjectItem.Kind.FOLDER)) {
            return;
        }
        String newName = Dialogs.input(frame, "Rename", "New name for \"" + item.name() + "\":", item.name());
        if (newName == null || newName.equals(item.name())) {
            return;
        }
        String connectionId = conn.id();
        BusyDialog.run(frame, "Preparing rename", "Listing objects", "rename objects in " + item.path().bucket(),
                (cancelled, progress) -> ctx.planner.planRename(connectionId, item, newName, cancelled, progress),
                tasks -> {
                    if (tasks.isEmpty()) {
                        Dialogs.info(frame, "Nothing to rename", "The folder contains no objects.");
                    } else {
                        ctx.transfers.submit(tasks);
                    }
                });
    }

    // --- copy / move / paste ---------------------------------------------------------------

    public void paste() {
        BrowserController.Clipboard clip = browser.clipboard();
        S3Path location = browser.location();
        S3Connection conn = browser.connection();
        if (clip == null || location == null || conn == null) {
            return;
        }
        transferItems(clip.connectionId(), clip.items(), conn.id(), location, clip.cut());
        if (clip.cut()) {
            browser.clearClipboard();
        }
    }

    public void copyOrMoveTo(List<S3ObjectItem> selection, boolean move) {
        S3Connection conn = requireConnection();
        List<S3ObjectItem> items = selection.stream()
                .filter(i -> i.isFile() || i.kind() == S3ObjectItem.Kind.FOLDER).toList();
        if (conn == null || items.isEmpty()) {
            return;
        }
        CopyMoveDialog.Destination dest = CopyMoveDialog.show(frame, ctx, move, conn.id(), browser.location());
        if (dest != null) {
            transferItems(conn.id(), items, dest.connectionId(), dest.folder(), move);
        }
    }

    /** Handles S3 items dropped on a folder (drag within the browser or onto the tree). */
    public void dropItems(String sourceConnectionId, List<S3ObjectItem> items, S3Path destFolder, boolean move) {
        S3Connection conn = browser.connection();
        if (conn == null || destFolder == null || items.isEmpty()) {
            return;
        }
        if (move) {
            String what = items.size() == 1 ? "\"" + items.get(0).name() + "\"" : items.size() + " items";
            if (!Dialogs.confirm(frame, "Move", "Move " + what + " to " + destFolder.toUri() + "?", "Move")) {
                return;
            }
        }
        transferItems(sourceConnectionId, items, conn.id(), destFolder, move);
    }

    private void transferItems(String sourceConnectionId, List<S3ObjectItem> items, String destConnectionId,
                               S3Path destFolder, boolean move) {
        boolean sameConnection = sourceConnectionId.equals(destConnectionId);
        List<S3ObjectItem> effective = items.stream()
                .filter(i -> !(sameConnection && move && destFolder.equals(i.path().parent())))
                .toList();
        if (effective.isEmpty()) {
            return; // moving items into the folder they are already in
        }
        Background.run(() -> ctx.planner.findConflicts(destConnectionId, effective, destFolder), conflicts -> {
            ConflictChoice choice = ConflictChoice.REPLACE;
            if (!conflicts.isEmpty()) {
                boolean allSameFolderCopies = sameConnection && !move && effective.stream()
                        .filter(i -> conflicts.contains(i.path()))
                        .allMatch(i -> destFolder.equals(i.path().parent()));
                choice = allSameFolderCopies ? ConflictChoice.KEEP_BOTH : askConflict(conflicts.size());
                if (choice == null) {
                    return;
                }
            }
            ConflictChoice finalChoice = choice;
            BusyDialog.run(frame, move ? "Preparing move" : "Preparing copy", "Listing objects",
                    (move ? "move" : "copy") + " objects to " + destFolder.bucket(),
                    (cancelled, progress) -> {
                        List<S3ObjectItem> toTransfer = new ArrayList<>();
                        Map<S3Path, String> renames = new HashMap<>();
                        S3Connection dest = ctx.connections.get(destConnectionId);
                        for (S3ObjectItem item : effective) {
                            if (conflicts.contains(item.path())) {
                                if (finalChoice == ConflictChoice.SKIP) {
                                    continue;
                                }
                                if (finalChoice == ConflictChoice.KEEP_BOTH) {
                                    String suffix = item.isFile() ? "" : "/";
                                    String unique = NameConflicts.uniqueName(item.name() + suffix, candidate ->
                                            ctx.browser.exists(dest, item.isFile()
                                                    ? destFolder.resolveObject(candidate)
                                                    : S3Path.folder(destFolder.bucket(), destFolder.key() + candidate)));
                                    renames.put(item.path(), item.isFile() ? unique : unique.substring(0, unique.length() - 1));
                                }
                            }
                            toTransfer.add(item);
                        }
                        return ctx.planner.planCopy(sourceConnectionId, toTransfer, destConnectionId, destFolder, move,
                                renames, cancelled, progress);
                    },
                    tasks -> {
                        if (tasks.isEmpty()) {
                            Dialogs.info(frame, "Nothing to transfer", "There are no objects to "
                                    + (move ? "move." : "copy."));
                        } else {
                            ctx.transfers.submit(tasks);
                        }
                    });
        }, error -> Dialogs.error(frame, error, "list the destination folder"));
    }

    private ConflictChoice askConflict(int count) {
        Object[] options = {"Replace", "Skip", "Keep Both", "Cancel"};
        String message = count == 1
                ? "An item with the same name already exists in the destination."
                : count + " items with the same names already exist in the destination.";
        int result = JOptionPane.showOptionDialog(frame, message, "Name Conflict", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        return switch (result) {
            case 0 -> ConflictChoice.REPLACE;
            case 1 -> ConflictChoice.SKIP;
            case 2 -> ConflictChoice.KEEP_BOTH;
            default -> null;
        };
    }

    // --- buckets -------------------------------------------------------------------------

    public void createBucket() {
        S3Connection conn = requireConnection();
        if (conn == null) {
            return;
        }
        String name = Dialogs.input(frame, "Create Bucket", "Bucket name (region " + conn.region().id() + "):", "");
        if (name == null || name.isEmpty()) {
            return;
        }
        Background.run(() -> {
            ctx.browser.createBucket(conn, name, conn.region().id());
            return null;
        }, ignored -> {
            if (browser.location() == null) {
                browser.refresh();
            }
        }, error -> Dialogs.error(frame, error, "create buckets"));
    }

    public void deleteBucket(S3ObjectItem item) {
        S3Connection conn = requireConnection();
        if (conn == null || item == null || item.kind() != S3ObjectItem.Kind.BUCKET) {
            return;
        }
        String typed = Dialogs.input(frame, "Delete Bucket",
                "This permanently deletes the empty bucket \"" + item.name() + "\".\nType the bucket name to confirm:", "");
        if (typed == null || !typed.equals(item.name())) {
            return;
        }
        Background.run(() -> {
            ctx.browser.deleteBucket(conn, item.name());
            return null;
        }, ignored -> browser.refresh(), error -> Dialogs.error(frame, error, "delete this bucket"));
    }
}
