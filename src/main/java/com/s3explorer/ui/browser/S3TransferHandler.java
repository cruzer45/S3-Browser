package com.s3explorer.ui.browser;

import com.s3explorer.controller.TransferController;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Drag and drop for S3 views: accepts local files (upload) and S3 items (move, or copy with
 * Ctrl), and exports the selection as S3 items for dropping on folders or the local panel.
 */
public class S3TransferHandler extends TransferHandler {

    private static final Logger log = LoggerFactory.getLogger(S3TransferHandler.class);

    private final Supplier<String> connectionId;
    private final Supplier<List<S3ObjectItem>> selection;
    private final Function<TransferSupport, S3Path> dropTarget;
    private final TransferController transfers;

    /**
     * @param selection  items to export when dragging, or null if this view is not a drag source
     * @param dropTarget resolves the folder a drop lands in; returns null to reject
     */
    public S3TransferHandler(Supplier<String> connectionId, Supplier<List<S3ObjectItem>> selection,
                             Function<TransferSupport, S3Path> dropTarget, TransferController transfers) {
        this.connectionId = connectionId;
        this.selection = selection;
        this.dropTarget = dropTarget;
        this.transfers = transfers;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return selection == null ? NONE : COPY_OR_MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        String conn = connectionId.get();
        List<S3ObjectItem> items = selection == null ? List.of() : selection.get().stream()
                .filter(i -> i.isFile() || i.kind() == S3ObjectItem.Kind.FOLDER).toList();
        if (conn == null || items.isEmpty()) {
            return null;
        }
        return new S3Selection(conn, items).transferable();
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (connectionId.get() == null) {
            return false;
        }
        boolean files = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        boolean s3 = support.isDataFlavorSupported(S3Selection.FLAVOR);
        if (!files && !s3) {
            return false;
        }
        if (files && support.isDrop() && (support.getSourceDropActions() & COPY) != 0) {
            // Never accept a MOVE from the OS: the file manager would delete the originals.
            support.setDropAction(COPY);
        }
        return dropTarget.apply(support) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        S3Path folder = dropTarget.apply(support);
        try {
            if (support.isDataFlavorSupported(S3Selection.FLAVOR)) {
                S3Selection sel = (S3Selection) support.getTransferable().getTransferData(S3Selection.FLAVOR);
                boolean move = !support.isDrop() || support.getDropAction() == MOVE;
                // Defer: showing dialogs inside the native drop callback can hang some platforms.
                SwingUtilities.invokeLater(() -> transfers.dropItems(sel.connectionId(), sel.items(), folder, move));
                return true;
            }
            List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            List<Path> paths = files.stream().map(File::toPath).toList();
            SwingUtilities.invokeLater(() -> transfers.uploadPaths(paths, folder, true));
            return true;
        } catch (Exception e) {
            log.warn("Drop failed", e);
            return false;
        }
    }
}
