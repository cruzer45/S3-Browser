package com.s3explorer.ui.browser;

import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** The main object table: sorting with folders first, quick filter, context menu, DnD. */
public class ObjectTablePanel extends JPanel {

    private final ObjectTableModel model = new ObjectTableModel();
    private final JTable table;
    private final TableRowSorter<ObjectTableModel> sorter = new TableRowSorter<>(model);
    private String emptyText = "Not connected";
    private String filter = "";

    private Consumer<S3ObjectItem> onActivate = item -> { };
    private Consumer<List<S3ObjectItem>> onSelection = items -> { };
    private Function<List<S3ObjectItem>, JPopupMenu> popupFactory = items -> null;

    public ObjectTablePanel() {
        super(new BorderLayout());
        table = new JTable(model) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0 || getRowCount() == 1 && model.items().get(0).isParent()) {
                    paintEmptyText(this, g);
                }
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) {
                    return null;
                }
                S3ObjectItem item = model.get(convertRowIndexToModel(row));
                return item.isParent() ? null : item.path().toUri();
            }
        };
        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.putClientProperty("JTable.autoStartsEdit", false);
        table.setToolTipText("");

        configureColumns();
        configureSorting();

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelection.accept(selectedItems());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        onActivate.accept(model.get(table.convertRowIndexToModel(row)));
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setComponentPopupMenu(null);
        scroll.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                table.clearSelection();
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    private void paintEmptyText(JTable t, Graphics g) {
        Color color = UIManager.getColor("Label.disabledForeground");
        g.setColor(color == null ? Color.GRAY : color);
        g.setFont(t.getFont());
        FontMetrics fm = g.getFontMetrics();
        int y = Math.max(t.getRowCount() * t.getRowHeight() + 40, t.getVisibleRect().height / 3);
        int x = (t.getVisibleRect().width - fm.stringWidth(emptyText)) / 2;
        g.drawString(emptyText, Math.max(8, x), y);
    }

    private void configureColumns() {
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(ObjectTableModel.NAME).setPreferredWidth(320);
        columns.getColumn(ObjectTableModel.SIZE).setPreferredWidth(90);
        columns.getColumn(ObjectTableModel.MODIFIED).setPreferredWidth(140);
        columns.getColumn(ObjectTableModel.TYPE).setPreferredWidth(120);
        columns.getColumn(ObjectTableModel.STORAGE_CLASS).setPreferredWidth(110);
        columns.getColumn(ObjectTableModel.ETAG).setPreferredWidth(220);

        columns.getColumn(ObjectTableModel.NAME).setCellRenderer(new ItemRenderer(ObjectTableModel.NAME));
        columns.getColumn(ObjectTableModel.SIZE).setCellRenderer(new ItemRenderer(ObjectTableModel.SIZE));
        columns.getColumn(ObjectTableModel.MODIFIED).setCellRenderer(new ItemRenderer(ObjectTableModel.MODIFIED));
        columns.getColumn(ObjectTableModel.TYPE).setCellRenderer(new ItemRenderer(ObjectTableModel.TYPE));
        columns.getColumn(ObjectTableModel.STORAGE_CLASS).setCellRenderer(new ItemRenderer(ObjectTableModel.STORAGE_CLASS));
        columns.getColumn(ObjectTableModel.ETAG).setCellRenderer(new ItemRenderer(ObjectTableModel.ETAG));
    }

    /**
     * Sorting keeps ".." first and folders before files regardless of direction. The row sorter
     * reverses comparators for descending order, so the group comparison is inverted to cancel that.
     */
    private void configureSorting() {
        for (int c = 0; c < model.getColumnCount(); c++) {
            int column = c;
            Comparator<S3ObjectItem> byValue = switch (column) {
                case ObjectTableModel.SIZE -> Comparator.comparingLong(S3ObjectItem::size);
                case ObjectTableModel.MODIFIED -> Comparator.comparing(S3ObjectItem::lastModified,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
                case ObjectTableModel.TYPE -> Comparator.comparing(S3ObjectItem::typeDescription,
                        String.CASE_INSENSITIVE_ORDER);
                case ObjectTableModel.STORAGE_CLASS -> Comparator.comparing(i -> i.storageClass() == null ? ""
                        : i.storageClass());
                case ObjectTableModel.ETAG -> Comparator.comparing(S3ObjectItem::cleanETag);
                default -> Comparator.comparing(i -> i.name().toLowerCase(Locale.ROOT));
            };
            sorter.setComparator(column, (Comparator<S3ObjectItem>) (a, b) -> {
                int group = Integer.compare(group(a), group(b));
                if (group != 0) {
                    return descending(column) ? -group : group;
                }
                int result = byValue.compare(a, b);
                return result != 0 ? result : a.name().compareToIgnoreCase(b.name());
            });
        }
        sorter.setSortsOnUpdates(true);
        sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(ObjectTableModel.NAME, SortOrder.ASCENDING)));
    }

    private boolean descending(int column) {
        List<? extends javax.swing.RowSorter.SortKey> keys = sorter.getSortKeys();
        return !keys.isEmpty() && keys.get(0).getColumn() == column
                && keys.get(0).getSortOrder() == SortOrder.DESCENDING;
    }

    private static int group(S3ObjectItem item) {
        return switch (item.kind()) {
            case PARENT -> 0;
            case BUCKET, FOLDER -> 1;
            case FILE -> 2;
        };
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        if (e.getComponent() == table) {
            int row = table.rowAtPoint(e.getPoint());
            if (row >= 0 && !table.isRowSelected(row)) {
                table.setRowSelectionInterval(row, row);
            } else if (row < 0) {
                table.clearSelection();
            }
        }
        JPopupMenu menu = popupFactory.apply(selectedItems());
        if (menu != null) {
            menu.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    // --- public API ----------------------------------------------------------------------

    public JTable table() {
        return table;
    }

    public void setItems(List<S3ObjectItem> items, String emptyMessage) {
        List<S3Path> previouslySelected = selectedItems().stream().filter(i -> !i.isParent())
                .map(S3ObjectItem::path).toList();
        this.emptyText = emptyMessage;
        model.setItems(items);
        if (!previouslySelected.isEmpty()) {
            for (int i = 0; i < table.getRowCount(); i++) {
                S3ObjectItem row = model.get(table.convertRowIndexToModel(i));
                if (!row.isParent() && previouslySelected.contains(row.path())) {
                    table.addRowSelectionInterval(i, i);
                }
            }
        }
        table.repaint();
    }

    public void clearSelection() {
        table.clearSelection();
    }

    public void setEmptyText(String text) {
        this.emptyText = text;
        table.repaint();
    }

    public List<S3ObjectItem> selectedItems() {
        List<S3ObjectItem> selected = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            selected.add(model.get(table.convertRowIndexToModel(row)));
        }
        return selected;
    }

    /** The item under the drop location, if it is a folder or bucket. */
    public S3ObjectItem containerAtDrop(TransferHandler.TransferSupport support) {
        if (!support.isDrop()) {
            return null;
        }
        JTable.DropLocation location = (JTable.DropLocation) support.getDropLocation();
        int row = location.getRow();
        if (row < 0 || row >= table.getRowCount()) {
            return null;
        }
        S3ObjectItem item = model.get(table.convertRowIndexToModel(row));
        return item.isContainer() ? item : null;
    }

    public List<S3ObjectItem> allItems() {
        return model.items();
    }

    public void setFilter(String text) {
        filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends ObjectTableModel, ? extends Integer> entry) {
                    S3ObjectItem item = entry.getModel().get(entry.getIdentifier());
                    return item.isParent() || item.name().toLowerCase(Locale.ROOT).contains(filter);
                }
            });
        }
    }

    public void selectFirstRow() {
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    public void onActivate(Consumer<S3ObjectItem> handler) {
        this.onActivate = handler;
    }

    public void onSelection(Consumer<List<S3ObjectItem>> handler) {
        this.onSelection = handler;
    }

    public void setPopupFactory(Function<List<S3ObjectItem>, JPopupMenu> factory) {
        this.popupFactory = factory;
    }

    /** Routes a key to an action while the table has focus, overriding JTable's own binding. */
    public void bindKey(KeyStroke key, String name, Action action) {
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        table.getActionMap().put(name, action);
    }

    /** Replaces JTable's built-in copy/cut/paste keyboard actions with ours. */
    public void bindClipboard(Action copy, Action cut, Action paste) {
        table.getActionMap().put(TransferHandler.getCopyAction().getValue(Action.NAME), copy);
        table.getActionMap().put(TransferHandler.getCutAction().getValue(Action.NAME), cut);
        table.getActionMap().put(TransferHandler.getPasteAction().getValue(Action.NAME), paste);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "s3copy", copy);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_X, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "s3cut", cut);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "s3paste", paste);
    }

    private static final class ItemRenderer extends DefaultTableCellRenderer {
        private final int column;

        ItemRenderer(int column) {
            this.column = column;
            if (column == ObjectTableModel.SIZE) {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int col) {
            S3ObjectItem item = (S3ObjectItem) value;
            String text = switch (column) {
                case ObjectTableModel.NAME -> item.isParent() ? ".." : item.name();
                case ObjectTableModel.SIZE -> item.isFile() ? FileSizeFormatter.format(item.size()) : "";
                case ObjectTableModel.MODIFIED -> DateTimeUtils.format(item.lastModified());
                case ObjectTableModel.TYPE -> item.typeDescription();
                case ObjectTableModel.STORAGE_CLASS -> item.isFile()
                        ? (item.storageClass() == null ? "STANDARD" : item.storageClass()) : "";
                case ObjectTableModel.ETAG -> item.cleanETag();
                default -> "";
            };
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, text, selected, false, row, col);
            label.setIcon(column == ObjectTableModel.NAME ? Icons.forItem(item) : null);
            label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (column == ObjectTableModel.ETAG && !selected) {
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return label;
        }
    }
}
