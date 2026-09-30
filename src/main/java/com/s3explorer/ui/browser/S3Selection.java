package com.s3explorer.ui.browser;

import com.s3explorer.model.S3ObjectItem;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.List;

/** S3 items being dragged within the application. */
public record S3Selection(String connectionId, List<S3ObjectItem> items) {

    public static final DataFlavor FLAVOR = createFlavor();

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + S3Selection.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public Transferable transferable() {
        S3Selection self = this;
        String text = String.join("\n", items.stream().map(i -> i.path().toUri()).toList());
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{FLAVOR, DataFlavor.stringFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (FLAVOR.equals(flavor)) {
                    return self;
                }
                if (DataFlavor.stringFlavor.equals(flavor)) {
                    return text;
                }
                throw new UnsupportedFlavorException(flavor);
            }
        };
    }
}
