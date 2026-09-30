package com.s3explorer.ui.browser;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ListingPage;
import com.s3explorer.model.S3BucketInfo;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.Background;
import com.s3explorer.util.ErrorTranslator;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Left-hand navigation: the connection's buckets with lazily loaded folders. */
public class BucketTreePanel extends JPanel {

    private static final int MAX_FOLDER_PAGES = 10;

    /** Tree node payload. {@code path} is null for the connection root. */
    record Node(String label, S3Path path, Kind kind) {
        enum Kind { ROOT, BUCKET, FOLDER, PLACEHOLDER }

        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext ctx;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node("Not connected", null, Node.Kind.ROOT));
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private S3Connection connection;
    private Consumer<S3Path> onNavigate = p -> { };
    private boolean suppressSelection;

    public BucketTreePanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setDropMode(DropMode.ON);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                          boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                Object user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof Node n) {
                    setIcon(switch (n.kind()) {
                        case ROOT -> Icons.get("connection");
                        case BUCKET -> Icons.get("bucket");
                        case FOLDER -> Icons.get(expanded ? "folder-open" : "folder");
                        case PLACEHOLDER -> null;
                    });
                }
                return this;
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (suppressSelection || connection == null) {
                return;
            }
            TreePath path = e.getNewLeadSelectionPath();
            if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Node n
                    && n.kind() != Node.Kind.PLACEHOLDER) {
                onNavigate.accept(n.path());
            }
        });

        JLabel header = new JLabel("Buckets");
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        add(header, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    public JTree tree() {
        return tree;
    }

    public void onNavigate(Consumer<S3Path> handler) {
        this.onNavigate = handler;
    }

    public void setConnection(S3Connection connection) {
        this.connection = connection;
        root.removeAllChildren();
        root.setUserObject(new Node(connection == null ? "Not connected" : connection.profile().name, null,
                Node.Kind.ROOT));
        if (connection != null) {
            root.add(placeholder("Loading…"));
        }
        model.reload();
    }

    public void setBuckets(List<S3BucketInfo> buckets) {
        root.removeAllChildren();
        for (S3BucketInfo b : buckets) {
            root.add(expandable(new Node(b.name(), S3Path.root(b.name()), Node.Kind.BUCKET)));
        }
        if (buckets.isEmpty()) {
            root.add(placeholder("No buckets"));
        }
        model.reload();
        tree.expandRow(0);
    }

    /** Highlights the node for a location if it is already loaded, without navigating. */
    public void reveal(S3Path location) {
        suppressSelection = true;
        try {
            if (location == null) {
                tree.setSelectionRow(0);
                return;
            }
            DefaultMutableTreeNode match = find(root, location);
            if (match != null) {
                TreePath path = new TreePath(match.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            } else {
                tree.clearSelection();
            }
        } finally {
            suppressSelection = false;
        }
    }

    private DefaultMutableTreeNode find(DefaultMutableTreeNode node, S3Path target) {
        if (node.getUserObject() instanceof Node n && target.equals(n.path())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof Node n && n.path() != null
                    && n.path().bucket().equals(target.bucket()) && target.key().startsWith(n.path().key())) {
                DefaultMutableTreeNode found = find(child, target);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Folder path for the node under a drop, or null. */
    public S3Path dropFolder(TransferHandler.TransferSupport support) {
        if (!support.isDrop()) {
            return null;
        }
        JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
        TreePath path = location.getPath();
        if (path == null) {
            return null;
        }
        Object user = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return user instanceof Node n && (n.kind() == Node.Kind.BUCKET || n.kind() == Node.Kind.FOLDER) ? n.path() : null;
    }

    private static DefaultMutableTreeNode expandable(Node node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        treeNode.add(placeholder("Loading…"));
        return treeNode;
    }

    private static DefaultMutableTreeNode placeholder(String text) {
        return new DefaultMutableTreeNode(new Node(text, null, Node.Kind.PLACEHOLDER), false);
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Node n) || n.path() == null || connection == null) {
            return;
        }
        if (node.getChildCount() != 1 || !(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject()
                instanceof Node child && child.kind() == Node.Kind.PLACEHOLDER && child.label().equals("Loading…"))) {
            return; // already loaded
        }
        S3Connection conn = connection;
        Background.run(() -> {
            List<S3Path> folders = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                ListingPage page = ctx.browser.list(conn, n.path(), token, ctx.settings.get().general.showHiddenObjects);
                for (S3ObjectItem item : page.items()) {
                    if (item.kind() == S3ObjectItem.Kind.FOLDER) {
                        folders.add(item.path());
                    }
                }
                token = page.nextToken();
            } while (token != null && ++pages < MAX_FOLDER_PAGES);
            return folders;
        }, folders -> {
            if (conn != connection) {
                return;
            }
            node.removeAllChildren();
            for (S3Path folder : folders) {
                node.add(expandable(new Node(folder.name(), folder, Node.Kind.FOLDER)));
            }
            model.nodeStructureChanged(node);
        }, error -> {
            if (conn != connection) {
                return;
            }
            node.removeAllChildren();
            node.add(placeholder(ErrorTranslator.translate(error, "list this bucket").title()));
            model.nodeStructureChanged(node);
        });
    }

    /** Reloads a folder's children next time it expands (after uploads, deletes, etc.). */
    public void invalidate(S3Path folder) {
        DefaultMutableTreeNode node = folder == null ? null : find(root, folder);
        if (node != null && node != root) {
            boolean expanded = tree.isExpanded(new TreePath(node.getPath()));
            node.removeAllChildren();
            node.add(placeholder("Loading…"));
            model.nodeStructureChanged(node);
            if (expanded) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
    }
}
