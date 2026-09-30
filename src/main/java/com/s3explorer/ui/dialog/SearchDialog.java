package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.controller.BrowserController;
import com.s3explorer.controller.ObjectController;
import com.s3explorer.controller.TransferController;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.SearchCriteria;
import com.s3explorer.service.SearchService;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.Background;
import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-modal search window that filters keys under a folder or the whole bucket. */
public final class SearchDialog extends JDialog {

    private final AppContext ctx;
    private final BrowserController browser;
    private final TransferController transfers;
    private final ObjectController objects;

    private final JLabel scopeLabel = new JLabel();
    private final JTextField name = new JTextField(22);
    private final JTextField extension = new JTextField(8);
    private final JTextField after = new JTextField(10);
    private final JTextField before = new JTextField(10);
    private final JTextField minSize = new JTextField(8);
    private final JTextField maxSize = new JTextField(8);
    private final JComboBox<String> storageClass = new JComboBox<>(new String[]{
            "", "STANDARD", "STANDARD_IA", "ONEZONE_IA", "INTELLIGENT_TIERING", "GLACIER_IR", "GLACIER",
            "DEEP_ARCHIVE", "REDUCED_REDUNDANCY"});
    private final JComboBox<String> scope = new JComboBox<>(new String[]{
            "This folder and subfolders", "This folder only", "Entire bucket"});
    private final JButton searchButton = new JButton("Search");
    private final JLabel status = new JLabel(" ");
    private final ResultModel results = new ResultModel();
    private final JTable table = new JTable(results);

    private S3Connection connection;
    private S3Path folder;
    private AtomicBoolean running;

    public SearchDialog(Component parent, AppContext ctx, BrowserController browser, TransferController transfers,
                        ObjectController objects) {
        super(SwingUtilities.getWindowAncestor(parent) != null ? SwingUtilities.getWindowAncestor(parent)
                : (java.awt.Window) parent, "Search", ModalityType.MODELESS);
        this.ctx = ctx;
        this.browser = browser;
        this.transfers = transfers;
        this.objects = objects;

        name.putClientProperty("JTextField.placeholderText", "*.backup, report, invoice-??.pdf");
        extension.putClientProperty("JTextField.placeholderText", "pdf");
        after.putClientProperty("JTextField.placeholderText", "yyyy-mm-dd");
        before.putClientProperty("JTextField.placeholderText", "yyyy-mm-dd");
        minSize.putClientProperty("JTextField.placeholderText", "MB");
        maxSize.putClientProperty("JTextField.placeholderText", "MB");

        JPanel dates = row(new JLabel("after"), after, new JLabel("before"), before);
        JPanel sizes = row(new JLabel("min"), minSize, new JLabel("max"), maxSize, new JLabel("MB"));
        FormPanel criteria = new FormPanel()
                .row("Search in:", scopeLabel)
                .row("Scope:", scope)
                .row("Name:", name)
                .row("Extension:", extension)
                .row("Modified:", dates)
                .row("Size:", sizes)
                .row("Storage class:", storageClass)
                .wide(row(searchButton, status));

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setIcon(Icons.forFileName(String.valueOf(value)));
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    goToFolder();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });

        searchButton.addActionListener(e -> {
            if (running != null) {
                running.set(true);
            } else {
                startSearch();
            }
        });
        JButton goTo = new JButton("Go to Folder");
        JButton download = new JButton("Download", Icons.get("download"));
        JButton close = new JButton("Close");
        goTo.addActionListener(e -> goToFolder());
        download.addActionListener(e -> transfers.download(selected()));
        close.addActionListener(e -> setVisible(false));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, criteria, new JScrollPane(table));
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setResizeWeight(0);
        JPanel content = new JPanel(new BorderLayout());
        content.add(split, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(goTo, download, close), BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, searchButton);
        setSize(820, 640);
        setLocationRelativeTo(getOwner());
    }

    private static JPanel row(Component... components) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Component c : components) {
            p.add(c);
        }
        return p;
    }

    public void showFor(S3Connection conn, S3Path location) {
        if (running != null) {
            running.set(true);
        }
        this.connection = conn;
        this.folder = location;
        scopeLabel.setText(conn.profile().name + "  ·  " + location.toUri());
        setVisible(true);
        name.requestFocusInWindow();
    }

    private void startSearch() {
        SearchCriteria criteria;
        try {
            criteria = new SearchCriteria(name.getText(), extension.getText(), date(after), date(before),
                    megabytes(minSize), megabytes(maxSize), (String) storageClass.getSelectedItem(),
                    scope.getSelectedIndex() != 1);
        } catch (IllegalArgumentException e) {
            Dialogs.error(this, "Check the search", e.getMessage());
            return;
        }
        S3Path searchRoot = scope.getSelectedIndex() == 2 ? S3Path.root(folder.bucket()) : folder;
        S3Connection conn = connection;
        results.clear();
        AtomicBoolean cancel = new AtomicBoolean();
        running = cancel;
        searchButton.setText("Stop");
        status.setText("Searching…");
        Background.run(() -> ctx.search.search(conn, searchRoot, criteria, cancel::get,
                batch -> SwingUtilities.invokeLater(() -> results.add(batch)),
                progress -> SwingUtilities.invokeLater(() -> status.setText(describe(progress, false)))),
                done -> finish(cancel, describe(done, cancel.get())),
                error -> {
                    finish(cancel, "Search failed.");
                    Dialogs.error(this, error, "list objects in " + searchRoot.bucket());
                });
    }

    private void finish(AtomicBoolean token, String message) {
        if (running == token) {
            running = null;
            searchButton.setText("Search");
        }
        status.setText(message);
    }

    private static String describe(SearchService.Progress p, boolean stopped) {
        String text = String.format("%,d match%s in %,d objects scanned", p.matched(), p.matched() == 1 ? "" : "es",
                p.scanned());
        if (p.limitReached()) {
            text += " (result limit reached)";
        } else if (stopped) {
            text += " (stopped)";
        }
        return text;
    }

    private static LocalDate date(JTextField field) {
        String t = field.getText().trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must use the format yyyy-mm-dd, for example 2026-07-01.");
        }
    }

    private static Long megabytes(JTextField field) {
        String t = field.getText().trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return (long) (Double.parseDouble(t) * 1024 * 1024);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Sizes must be numbers of megabytes, for example 1.5.");
        }
    }

    private List<S3ObjectItem> selected() {
        List<S3ObjectItem> list = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            list.add(results.get(table.convertRowIndexToModel(row)));
        }
        return list;
    }

    private void goToFolder() {
        List<S3ObjectItem> sel = selected();
        if (!sel.isEmpty() && browser.connection() == connection) {
            browser.navigate(sel.get(0).path().parent());
        }
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        List<S3ObjectItem> sel = selected();
        if (sel.isEmpty()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Go to Folder", "folder-open", this::goToFolder));
        menu.add(item("Download", "download", () -> transfers.download(sel)));
        menu.add(item("Copy S3 URI", "copy", () -> objects.copyS3Uris(sel)));
        menu.add(item("Properties", "properties", () -> {
            if (browser.connection() == connection) {
                objects.properties(sel.get(0));
            }
        }));
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, String icon, Runnable action) {
        JMenuItem mi = new JMenuItem(label, Icons.get(icon));
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private static final class ResultModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Folder", "Size", "Last Modified", "Storage Class"};
        private final List<S3ObjectItem> rows = new ArrayList<>();

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        void add(List<S3ObjectItem> batch) {
            int start = rows.size();
            rows.addAll(batch);
            fireTableRowsInserted(start, rows.size() - 1);
        }

        S3ObjectItem get(int row) {
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
            return column == 2 ? SizeValue.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            S3ObjectItem item = rows.get(row);
            return switch (column) {
                case 0 -> item.name();
                case 1 -> item.path().parent() == null ? "/" : "/" + item.path().parent().key();
                case 2 -> new SizeValue(item.size());
                case 3 -> DateTimeUtils.format(item.lastModified());
                default -> item.storageClass() == null ? "STANDARD" : item.storageClass();
            };
        }
    }

    /** Sorts numerically, displays formatted. */
    record SizeValue(long bytes) implements Comparable<SizeValue> {
        @Override
        public int compareTo(SizeValue o) {
            return Comparator.<Long>naturalOrder().compare(bytes, o.bytes);
        }

        @Override
        public String toString() {
            return FileSizeFormatter.format(bytes);
        }
    }
}
