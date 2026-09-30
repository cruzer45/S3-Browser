package com.s3explorer.ui.browser;

import com.s3explorer.ui.Icons;
import com.s3explorer.util.Background;
import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The local filesystem pane of dual-pane mode. Drag files from here to S3 to upload, or drop
 * S3 items here to download into the current directory.
 */
public class LocalFilePanel extends JPanel {

    record LocalEntry(Path path, String name, boolean directory, boolean parent, long size, Instant modified) {
    }

    private final LocalModel model = new LocalModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<LocalModel> sorter = new TableRowSorter<>(model);
    private final JTextField pathField = new JTextField();
    private final JLabel status = new JLabel(" ");
    private Path current;
    private int generation;
    private Consumer<List<Path>> onUpload = paths -> { };
    private BiConsumer<S3Selection, Path> onS3Drop = (sel, dir) -> { };

    public LocalFilePanel(Path start) {
        super(new BorderLayout());
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("up", "Parent folder", () -> {
            if (current != null && current.getParent() != null) {
                navigate(current.getParent());
            }
        }));
        bar.add(button("home", "Home folder", () -> navigate(Path.of(System.getProperty("user.home")))));
        bar.add(button("refresh", "Refresh", this::refresh));
        bar.addSeparator();
        JButton upload = new JButton("Upload", Icons.get("upload"));
        upload.setToolTipText("Upload the selected local files to the current S3 folder");
        upload.addActionListener(e -> onUpload.accept(selectedPaths()));
        bar.add(upload);

        pathField.addActionListener(e -> {
            Path p = Path.of(pathField.getText().trim());
            if (Files.isDirectory(p)) {
                navigate(p);
            } else {
                pathField.setText(current == null ? "" : current.toString());
            }
        });
        JPanel top = new JPanel(new BorderLayout(4, 0));
        JLabel title = new JLabel("Local", Icons.get("drive"), SwingConstants.LEFT);
        title.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        top.add(title, BorderLayout.WEST);
        top.add(pathField, BorderLayout.CENTER);
        top.add(bar, BorderLayout.EAST);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(3, 0, 3, 4)));

        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                LocalEntry e = (LocalEntry) value;
                super.getTableCellRendererComponent(t, e.parent() ? ".." : e.name(), selected, false, row, column);
                setIcon(e.parent() ? Icons.get("up") : e.directory() ? Icons.get("folder") : Icons.forFileName(e.name()));
                return this;
            }
        });
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        sorter.setComparator(0, (Comparator<LocalEntry>) (a, b) -> {
            int ga = a.parent() ? 0 : a.directory() ? 1 : 2;
            int gb = b.parent() ? 0 : b.directory() ? 1 : 2;
            if (ga != gb) {
                boolean desc = !sorter.getSortKeys().isEmpty()
                        && sorter.getSortKeys().get(0).getSortOrder() == SortOrder.DESCENDING;
                return desc ? gb - ga : ga - gb;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    openSelected();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && current != null && current.getParent() != null) {
                    navigate(current.getParent());
                    e.consume();
                }
            }
        });
        table.setTransferHandler(new LocalTransferHandler());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        navigate(start != null && Files.isDirectory(start) ? start : Path.of(System.getProperty("user.home")));
    }

    private static JButton button(String icon, String tooltip, Runnable action) {
        JButton b = new JButton(Icons.get(icon));
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    public void onUpload(Consumer<List<Path>> handler) {
        this.onUpload = handler;
    }

    public void onS3Drop(BiConsumer<S3Selection, Path> handler) {
        this.onS3Drop = handler;
    }

    public Path currentDirectory() {
        return current;
    }

    public void navigate(Path dir) {
        Path target = dir.toAbsolutePath().normalize();
        int gen = ++generation;
        status.setText("Loading…");
        Background.run(() -> list(target), entries -> {
            if (gen != generation) {
                return;
            }
            current = target;
            pathField.setText(target.toString());
            model.set(entries);
            long files = entries.stream().filter(e -> !e.directory()).count();
            status.setText(String.format("%,d items", entries.size() - (target.getParent() == null ? 0 : 1))
                    + (files > 0 ? "  ·  " + FileSizeFormatter.format(entries.stream()
                    .filter(e -> !e.directory()).mapToLong(LocalEntry::size).sum()) : ""));
        }, error -> {
            if (gen == generation) {
                status.setText("Cannot open " + target + ": " + error.getMessage());
                pathField.setText(current == null ? "" : current.toString());
            }
        });
    }

    public void refresh() {
        if (current != null) {
            navigate(current);
        }
    }

    private static List<LocalEntry> list(Path dir) throws IOException {
        List<LocalEntry> entries = new ArrayList<>();
        if (dir.getParent() != null) {
            entries.add(new LocalEntry(dir.getParent(), "..", true, true, -1, null));
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    entries.add(new LocalEntry(p, p.getFileName().toString(), a.isDirectory(), false,
                            a.isDirectory() ? -1 : a.size(), a.lastModifiedTime().toInstant()));
                } catch (IOException ignored) {
                    // Unreadable entry (broken link, permissions): skip it.
                }
            }
        }
        return entries;
    }

    private void openSelected() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            LocalEntry e = model.get(table.convertRowIndexToModel(row));
            if (e.directory()) {
                navigate(e.path());
            }
        }
    }

    public List<Path> selectedPaths() {
        List<Path> paths = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            LocalEntry e = model.get(table.convertRowIndexToModel(row));
            if (!e.parent()) {
                paths.add(e.path());
            }
        }
        return paths;
    }

    private final class LocalTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            List<File> files = selectedPaths().stream().map(Path::toFile).toList();
            if (files.isEmpty()) {
                return null;
            }
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{DataFlavor.javaFileListFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.javaFileListFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedFlavorException(flavor);
                    }
                    return files;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(S3Selection.FLAVOR) || current == null) {
                return false;
            }
            if (support.isDrop() && (support.getSourceDropActions() & COPY) != 0) {
                support.setDropAction(COPY);
            }
            return true;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                S3Selection sel = (S3Selection) support.getTransferable().getTransferData(S3Selection.FLAVOR);
                Path target = current;
                if (support.isDrop()) {
                    int row = ((JTable.DropLocation) support.getDropLocation()).getRow();
                    if (row >= 0 && row < table.getRowCount()) {
                        LocalEntry e = model.get(table.convertRowIndexToModel(row));
                        if (e.directory() && !e.parent()) {
                            target = e.path();
                        }
                    }
                }
                Path dir = target;
                SwingUtilities.invokeLater(() -> onS3Drop.accept(sel, dir));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static final class LocalModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Size", "Modified"};
        private List<LocalEntry> rows = List.of();

        void set(List<LocalEntry> entries) {
            rows = entries;
            fireTableDataChanged();
        }

        LocalEntry get(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? LocalEntry.class : column == 1 ? SizeCell.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            LocalEntry e = rows.get(row);
            return switch (column) {
                case 0 -> e;
                case 1 -> new SizeCell(e.directory() ? -1 : e.size());
                default -> DateTimeUtils.format(e.modified());
            };
        }
    }

    /** Numeric sort, formatted display. */
    record SizeCell(long bytes) implements Comparable<SizeCell> {
        @Override
        public int compareTo(SizeCell o) {
            return Long.compare(bytes, o.bytes);
        }

        @Override
        public String toString() {
            return bytes < 0 ? "" : FileSizeFormatter.format(bytes);
        }
    }
}
