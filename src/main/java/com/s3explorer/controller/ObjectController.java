package com.s3explorer.controller;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AppSettings;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.dialog.ObjectPropertiesDialog;
import com.s3explorer.ui.dialog.PresignedUrlDialog;
import com.s3explorer.ui.dialog.PreviewDialog;
import com.s3explorer.ui.dialog.SearchDialog;
import com.s3explorer.util.Background;
import com.s3explorer.util.MimeTypeUtils;

import javax.swing.JFrame;
import java.util.List;
import java.util.stream.Collectors;

/** Object inspection: properties, metadata, URIs, presigned URLs, preview and search. */
public class ObjectController {

    private final AppContext ctx;
    private final JFrame frame;
    private final BrowserController browser;
    private final TransferController transfers;
    private SearchDialog searchDialog;

    public ObjectController(AppContext ctx, JFrame frame, BrowserController browser, TransferController transfers) {
        this.ctx = ctx;
        this.frame = frame;
        this.browser = browser;
        this.transfers = transfers;
    }

    /** Double-click or Enter on a row. */
    public void activate(S3ObjectItem item) {
        if (item == null) {
            return;
        }
        if (!item.isFile()) {
            browser.open(item);
            return;
        }
        AppSettings.DoubleClickAction action = ctx.settings.get().general.doubleClickAction;
        switch (action) {
            case PREVIEW -> {
                if (MimeTypeUtils.isPreviewable(item.name())) {
                    preview(item);
                } else {
                    properties(item);
                }
            }
            case DOWNLOAD -> transfers.download(List.of(item));
            case PROPERTIES -> properties(item);
        }
    }

    public void properties(S3ObjectItem item) {
        S3Connection conn = browser.connection();
        if (conn == null || item == null || item.isParent()) {
            return;
        }
        ObjectPropertiesDialog.show(frame, ctx, conn, item, browser::refresh);
    }

    public void preview(S3ObjectItem item) {
        S3Connection conn = browser.connection();
        if (conn == null || item == null || !item.isFile()) {
            return;
        }
        PreviewDialog.show(frame, ctx, conn, item.path());
    }

    public void presign(S3ObjectItem item) {
        S3Connection conn = browser.connection();
        if (conn == null || item == null || !item.isFile()) {
            return;
        }
        PresignedUrlDialog.show(frame, ctx, conn, item.path());
    }

    public void copyS3Uris(List<S3ObjectItem> items) {
        String text = items.stream().filter(i -> !i.isParent()).map(i -> i.path().toUri())
                .collect(Collectors.joining("\n"));
        if (!text.isEmpty()) {
            Dialogs.copyToClipboard(text);
        }
    }

    public void copyObjectUrls(List<S3ObjectItem> items) {
        S3Connection conn = browser.connection();
        List<S3ObjectItem> usable = items.stream().filter(i -> !i.isParent()).toList();
        if (conn == null || usable.isEmpty()) {
            return;
        }
        Background.run(() -> usable.stream().map(i -> ctx.browser.objectUrl(conn, i.path()))
                        .collect(Collectors.joining("\n")),
                Dialogs::copyToClipboard,
                error -> Dialogs.error(frame, error, "read this bucket"));
    }

    public void openSearch() {
        S3Connection conn = browser.connection();
        if (conn == null) {
            Dialogs.info(frame, "Not connected", "Connect to a storage account first.");
            return;
        }
        if (browser.location() == null) {
            Dialogs.info(frame, "Open a bucket", "Open a bucket or folder to search in.");
            return;
        }
        if (searchDialog == null || !searchDialog.isDisplayable()) {
            searchDialog = new SearchDialog(frame, ctx, browser, transfers, this);
        }
        searchDialog.showFor(conn, browser.location());
    }
}
