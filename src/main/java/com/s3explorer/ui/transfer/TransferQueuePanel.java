package com.s3explorer.ui.transfer;

import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.service.ConnectionService;
import com.s3explorer.service.TransferService;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.FileSizeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The transfer queue: progress, speed, ETA, and pause/resume/cancel/retry controls. */
public class TransferQueuePanel extends JPanel {

    private static final String[] COLUMNS = {"Name", "Operation", "Source", "Destination", "Size", "Transferred",
            "Progress", "Speed", "Status", "Elapsed", "Remaining"};

    private final TransferService transfers;
    private final ConnectionService connections;
    private final QueueModel model = new QueueModel();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel("Transfers");
    private final JButton pause = tool("pause", "Pause", this::pauseSelected);
    private final JButton resume = tool("resume", "Resume", this::resumeSelected);
    private final JButton cancel = tool("cancel", "Cancel", this::cancelSelected);
    private final JButton retry = tool("retry", "Retry", this::retrySelected);
    private final JButton remove = tool("delete", "Remove from list", this::removeSelected);
    private Consumer<Integer> onActiveCountChanged = n -> { };

    public TransferQueuePanel(TransferService transfers, ConnectionService connections) {
        super(new BorderLayout());
        this.transfers = transfers;
        this.connections = connections;

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        summary.setIcon(Icons.get("transfers"));
        summary.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 12));
        bar.add(summary);
        bar.add(pause);
        bar.add(resume);
        bar.add(cancel);
        bar.add(retry);
        bar.add(remove);
        bar.addSeparator();
        bar.add(textTool("Pause All", transfers::pauseAll));
        bar.add(textTool("Resume All", transfers::resumeAll));
        bar.add(textTool("Clear Completed", transfers::clearCompleted));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(false);
        int[] widths = {200, 70, 220, 220, 80, 90, 130, 80, 150, 60, 70};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(6).setCellRenderer(new ProgressRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c : new int[]{4, 5, 7, 9, 10}) {
            table.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                TransferTask task = model.get(row);
                setIcon(switch (task.type()) {
                    case UPLOAD -> Icons.get("upload");
                    case DOWNLOAD -> Icons.get("download");
                    case COPY -> Icons.get("copy");
                    case MOVE -> Icons.get("cut");
                    case DELETE -> Icons.get("delete");
                });
                return this;
            }
        });
        table.getColumnModel().getColumn(8).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                TransferTask task = model.get(row);
                setToolTipText(task.message());
                if (!selected) {
                    Color fg = switch (task.status()) {
                        case FAILED -> UIManager.getColor("Actions.Red");
                        case COMPLETED -> UIManager.getColor("Actions.Green");
                        case PAUSED, CANCELLED -> UIManager.getColor("Label.disabledForeground");
                        default -> t.getForeground();
                    };
                    setForeground(fg == null ? t.getForeground() : fg);
                }
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        transfers.addListener(new TransferService.Listener() {
            @Override
            public void tasksAdded(List<TransferTask> tasks) {
                SwingUtilities.invokeLater(() -> model.add(tasks));
            }

            @Override
            public void tasksRemoved(List<TransferTask> tasks) {
                SwingUtilities.invokeLater(() -> model.remove(tasks));
            }

            @Override
            public void taskChanged(TransferTask task) {
                SwingUtilities.invokeLater(() -> model.changed(task));
            }
        });

        // Progress is polled rather than pushed, so fast transfers do not flood the EDT.
        new Timer(500, e -> tick()).start();
        updateButtons();
    }

    public void onActiveCountChanged(Consumer<Integer> handler) {
        this.onActiveCountChanged = handler;
    }

    private void tick() {
        boolean any = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            TransferTask t = model.get(i);
            t.sampleSpeed();
            if (t.status().isActive()) {
                model.fireTableRowsUpdated(i, i);
                any = true;
            }
        }
        updateSummary();
        if (any) {
            updateButtons();
        }
    }

    private void updateSummary() {
        int active = 0;
        int queued = 0;
        int failed = 0;
        int paused = 0;
        for (TransferTask t : model.rows) {
            switch (t.status()) {
                case STARTING, RUNNING -> active++;
                case QUEUED -> queued++;
                case FAILED -> failed++;
                case PAUSED -> paused++;
                default -> {
                }
            }
        }
        StringBuilder text = new StringBuilder("Transfers");
        List<String> parts = new ArrayList<>();
        if (active > 0) parts.add(active + " active");
        if (queued > 0) parts.add(queued + " queued");
        if (paused > 0) parts.add(paused + " paused");
        if (failed > 0) parts.add(failed + " failed");
        if (!parts.isEmpty()) {
            text.append(" (").append(String.join(", ", parts)).append(')');
        }
        summary.setText(text.toString());
        onActiveCountChanged.accept(active + queued);
    }

    private List<TransferTask> selected() {
        List<TransferTask> list = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            list.add(model.get(row));
        }
        return list;
    }

    private void updateButtons() {
        List<TransferTask> sel = selected();
        pause.setEnabled(sel.stream().anyMatch(t -> t.status() == TransferStatus.QUEUED || t.status().isActive()));
        resume.setEnabled(sel.stream().anyMatch(t -> t.status() == TransferStatus.PAUSED));
        cancel.setEnabled(sel.stream().anyMatch(t -> !t.status().isFinished()));
        retry.setEnabled(sel.stream().anyMatch(t -> t.status() == TransferStatus.FAILED
                || t.status() == TransferStatus.CANCELLED));
        remove.setEnabled(sel.stream().anyMatch(t -> !t.status().isActive()));
    }

    private void pauseSelected() {
        selected().forEach(transfers::pause);
    }

    private void resumeSelected() {
        selected().forEach(transfers::resume);
    }

    private void cancelSelected() {
        selected().forEach(transfers::cancel);
    }

    private void retrySelected() {
        selected().forEach(transfers::retry);
    }

    private void removeSelected() {
        transfers.remove(selected());
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        updateButtons();
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(pause));
        menu.add(menuItem(resume));
        menu.add(menuItem(cancel));
        menu.add(menuItem(retry));
        menu.add(menuItem(remove));
        List<TransferTask> sel = selected();
        if (sel.size() == 1 && sel.get(0).message() != null) {
            menu.addSeparator();
            JMenuItem details = new JMenuItem("Show Details");
            details.addActionListener(ev -> com.s3explorer.ui.Dialogs.info(this, sel.get(0).displayName(),
                    sel.get(0).message()));
            menu.add(details);
        }
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem menuItem(JButton source) {
        JMenuItem item = new JMenuItem(source.getToolTipText(), source.getIcon());
        item.setEnabled(source.isEnabled());
        item.addActionListener(e -> source.doClick());
        return item;
    }

    private static JButton tool(String icon, String tooltip, Runnable action) {
        JButton b = new JButton(Icons.get(icon));
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JButton textTool(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** S3 location, prefixed with the connection name when a task spans two connections. */
    private String describe(TransferTask task, String connectionId, S3Path path) {
        if (path == null) {
            return "";
        }
        boolean crossConnection = task.sourceConnectionId() != null && task.destConnectionId() != null
                && !task.sourceConnectionId().equals(task.destConnectionId());
        return crossConnection ? connections.displayName(connectionId) + ": " + path.toUri() : path.toUri();
    }

    private final class QueueModel extends AbstractTableModel {
        private final List<TransferTask> rows = new ArrayList<>();

        void add(List<TransferTask> tasks) {
            int start = rows.size();
            rows.addAll(tasks);
            fireTableRowsInserted(start, rows.size() - 1);
            updateSummary();
        }

        void remove(List<TransferTask> tasks) {
            rows.removeAll(tasks);
            fireTableDataChanged();
            updateSummary();
            updateButtons();
        }

        void changed(TransferTask task) {
            int i = rows.indexOf(task);
            if (i >= 0) {
                fireTableRowsUpdated(i, i);
            }
            updateSummary();
            updateButtons();
        }

        TransferTask get(int row) {
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
        public Object getValueAt(int row, int column) {
            TransferTask t = rows.get(row);
            boolean objects = t.unit() == TransferTask.Unit.OBJECTS;
            return switch (column) {
                case 0 -> t.displayName();
                case 1 -> t.type().toString();
                case 2 -> switch (t.type()) {
                    case UPLOAD -> t.localPath().toString();
                    default -> describe(t, t.sourceConnectionId(), t.source());
                };
                case 3 -> switch (t.type()) {
                    case DOWNLOAD -> t.localPath() == null ? "" : t.localPath().toString();
                    case DELETE -> "";
                    default -> describe(t, t.destConnectionId(), t.destination());
                };
                case 4 -> t.total() < 0 ? "" : objects ? String.format("%,d objects", t.total())
                        : FileSizeFormatter.format(t.total());
                case 5 -> objects ? String.format("%,d", t.transferred()) : FileSizeFormatter.format(t.transferred());
                case 6 -> t;
                case 7 -> FileSizeFormatter.formatRate(t.speed());
                case 8 -> statusText(t);
                case 9 -> {
                    Duration d = t.elapsed();
                    yield d.isZero() ? "" : DateTimeUtils.formatDuration(d);
                }
                case 10 -> {
                    Duration d = t.remaining();
                    yield d == null ? "" : DateTimeUtils.formatDuration(d);
                }
                default -> "";
            };
        }

        private String statusText(TransferTask t) {
            String message = t.message();
            if (message == null || message.isBlank()) {
                return t.status().toString();
            }
            return t.status() == TransferStatus.RUNNING || t.status() == TransferStatus.COMPLETED
                    || t.status() == TransferStatus.FAILED ? t.status() + ": " + message : t.status().toString();
        }
    }

    private static final class ProgressRenderer implements TableCellRenderer {
        private final JProgressBar bar = new JProgressBar(0, 1000);

        ProgressRenderer() {
            bar.setStringPainted(true);
            bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            TransferTask t = (TransferTask) value;
            double f = t.progressFraction();
            bar.setValue((int) (f * 1000));
            bar.setString(t.total() < 0 && t.status() != TransferStatus.COMPLETED ? "" : Math.round(f * 100) + "%");
            bar.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            return bar;
        }
    }
}
