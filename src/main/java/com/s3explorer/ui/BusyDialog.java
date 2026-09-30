package com.s3explorer.ui;

import com.s3explorer.util.Background;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs a preparatory background job (such as enumerating a folder) and shows a small
 * cancellable progress dialog if it takes longer than a moment.
 */
public final class BusyDialog {

    @FunctionalInterface
    public interface Job<T> {
        T run(BooleanSupplier cancelled, Consumer<Integer> progress) throws Exception;
    }

    private BusyDialog() {
    }

    /**
     * @param message shown with the running count, e.g. "Scanning objects" -> "Scanning objects… 1,200"
     * @param action  used for error messages, following "permission to"
     */
    public static <T> void run(Component parent, String title, String message, String action, Job<T> job,
                               Consumer<T> onSuccess) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null && parent instanceof Window w) {
            owner = w;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        JDialog dialog = new JDialog(owner, title);
        JLabel label = new JLabel(message + "…");
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            cancelled.set(true);
            label.setText("Cancelling…");
            cancel.setEnabled(false);
        });
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        content.add(label, BorderLayout.NORTH);
        content.add(bar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.pack();
        dialog.setSize(Math.max(360, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(owner);

        Timer showTimer = new Timer(400, e -> dialog.setVisible(true));
        showTimer.setRepeats(false);
        showTimer.start();

        Consumer<Integer> progress = n -> SwingUtilities.invokeLater(() -> {
            if (!cancelled.get()) {
                label.setText(message + "… " + String.format("%,d", n));
            }
        });
        Background.run(() -> job.run(cancelled::get, progress), result -> {
            showTimer.stop();
            dialog.dispose();
            if (!cancelled.get()) {
                onSuccess.accept(result);
            }
        }, error -> {
            showTimer.stop();
            dialog.dispose();
            if (!cancelled.get()) {
                Dialogs.error(parent, error, action);
            }
        });
    }
}
