package com.s3explorer.controller;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ListingPage;
import com.s3explorer.model.S3BucketInfo;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.util.Background;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Navigation state for the object browser: the active connection, the current location
 * (null means the bucket list), back/forward history, and the loaded listing. Listings are
 * fetched page by page in the background and appended as they arrive. All public methods
 * must be called on the EDT; listeners are notified on the EDT.
 */
public class BrowserController {

    private static final Logger log = LoggerFactory.getLogger(BrowserController.class);

    public interface Listener {
        default void connectionChanged(S3Connection connection) {
        }

        default void locationChanged(S3Path location) {
        }

        /** Items replaced or appended; {@code loading} is true while more pages are coming. */
        default void itemsChanged(List<S3ObjectItem> items, boolean loading) {
        }

        default void loadFailed(Throwable error) {
        }

        default void bucketsLoaded(List<S3BucketInfo> buckets) {
        }
    }

    /** Items placed on the internal clipboard by Copy or Cut. */
    public record Clipboard(String connectionId, S3Path sourceFolder, List<S3ObjectItem> items, boolean cut) {
    }

    private final AppContext ctx;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedList<S3Path> back = new LinkedList<>();
    private final LinkedList<S3Path> forward = new LinkedList<>();

    private S3Connection connection;
    private S3Path location;
    private List<S3ObjectItem> items = List.of();
    private List<S3BucketInfo> buckets = List.of();
    private boolean loading;
    private boolean connected;
    private int generation;
    private Clipboard clipboard;

    public BrowserController(AppContext ctx) {
        this.ctx = ctx;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public S3Connection connection() {
        return connection;
    }

    /** Null when showing the bucket list. */
    public S3Path location() {
        return location;
    }

    public List<S3ObjectItem> items() {
        return items;
    }

    public List<S3BucketInfo> buckets() {
        return buckets;
    }

    public boolean isLoading() {
        return loading;
    }

    /** True once the active connection has successfully listed buckets. */
    public boolean isConnected() {
        return connected;
    }

    public void setConnection(S3Connection newConnection) {
        this.connection = newConnection;
        this.connected = false;
        this.buckets = List.of();
        back.clear();
        forward.clear();
        listeners.forEach(l -> l.connectionChanged(newConnection));
        show(null);
    }

    public void navigate(S3Path target) {
        S3Path normalized = target == null ? null : S3Path.folder(target.bucket(), target.prefix());
        if (connection == null || equals(normalized, location)) {
            refresh();
            return;
        }
        back.push(location);
        forward.clear();
        show(normalized);
    }

    public void back() {
        if (!back.isEmpty()) {
            forward.push(location);
            show(back.pop());
        }
    }

    public void forward() {
        if (!forward.isEmpty()) {
            back.push(location);
            show(forward.pop());
        }
    }

    public void up() {
        if (location != null) {
            navigate(location.parent());
        }
    }

    public boolean canBack() {
        return !back.isEmpty();
    }

    public boolean canForward() {
        return !forward.isEmpty();
    }

    public boolean canUp() {
        return location != null;
    }

    /** Opens a row: navigates into buckets and folders, or up for "..". */
    public void open(S3ObjectItem item) {
        if (item.isParent()) {
            up();
        } else if (item.isContainer()) {
            navigate(item.path());
        }
    }

    public void refresh() {
        show(location);
    }

    /** Refreshes only if the given folder is the one currently displayed. */
    public void refreshIfShowing(String connectionId, S3Path folder) {
        if (connection != null && connection.id().equals(connectionId) && location != null && folder != null
                && location.bucket().equals(folder.bucket()) && location.key().equals(folder.prefix())) {
            refresh();
        }
    }

    private void show(S3Path target) {
        location = target;
        int gen = ++generation;
        listeners.forEach(l -> l.locationChanged(target));
        if (connection == null) {
            setItems(List.of(), false);
            return;
        }
        S3Connection conn = connection;
        List<S3ObjectItem> initial = new ArrayList<>();
        if (target != null) {
            initial.add(S3ObjectItem.parent(target));
        }
        setItems(initial, true);

        if (target == null) {
            Background.run(() -> ctx.browser.listBuckets(conn), result -> {
                if (gen != generation) {
                    return;
                }
                connected = true;
                buckets = result;
                listeners.forEach(l -> l.bucketsLoaded(result));
                setItems(result.stream().map(S3ObjectItem::bucket).toList(), false);
            }, error -> failed(gen, error));
            return;
        }

        boolean showHidden = ctx.settings.get().general.showHiddenObjects;
        Background.run(() -> {
            String token = null;
            do {
                if (gen != generation) {
                    return null;
                }
                ListingPage page = ctx.browser.list(conn, target, token, showHidden);
                boolean more = page.hasMore();
                SwingUtilities.invokeLater(() -> {
                    if (gen == generation) {
                        connected = true;
                        List<S3ObjectItem> merged = new ArrayList<>(items);
                        merged.addAll(page.items());
                        setItems(merged, more);
                    }
                });
                token = page.nextToken();
            } while (token != null);
            return null;
        }, null, error -> failed(gen, error));
    }

    private void failed(int gen, Throwable error) {
        if (gen != generation) {
            return;
        }
        log.warn("Listing {} failed: {}", location == null ? "buckets" : location.toUri(), error.toString());
        setItems(items, false);
        listeners.forEach(l -> l.loadFailed(error));
    }

    private void setItems(List<S3ObjectItem> newItems, boolean stillLoading) {
        items = List.copyOf(newItems);
        loading = stillLoading;
        listeners.forEach(l -> l.itemsChanged(items, stillLoading));
    }

    // --- internal clipboard ---------------------------------------------------------------

    public void copyToClipboard(List<S3ObjectItem> selection, boolean cut) {
        List<S3ObjectItem> usable = selection.stream()
                .filter(i -> i.isFile() || i.kind() == S3ObjectItem.Kind.FOLDER).toList();
        if (connection != null && !usable.isEmpty()) {
            clipboard = new Clipboard(connection.id(), location, usable, cut);
        }
    }

    public Clipboard clipboard() {
        return clipboard;
    }

    public void clearClipboard() {
        clipboard = null;
    }

    private static boolean equals(S3Path a, S3Path b) {
        return a == null ? b == null : a.equals(b);
    }
}
