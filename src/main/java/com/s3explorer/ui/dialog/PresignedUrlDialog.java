package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.service.PresignedUrlService;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.util.Background;
import com.s3explorer.util.DateTimeUtils;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Generates a presigned GET URL. The URL lives only in this dialog and the clipboard; it is
 * never stored or logged.
 */
public final class PresignedUrlDialog extends JDialog {

    private enum Expiry {
        MIN_15("15 minutes", Duration.ofMinutes(15)),
        HOUR_1("1 hour", Duration.ofHours(1)),
        HOUR_4("4 hours", Duration.ofHours(4)),
        HOUR_24("24 hours", Duration.ofHours(24)),
        DAYS_7("7 days (maximum)", Duration.ofDays(7)),
        CUSTOM("Custom…", null);

        final String label;
        final Duration duration;

        Expiry(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JComboBox<Expiry> expiry = new JComboBox<>(Expiry.values());
    private final JSpinner customValue = new JSpinner(new SpinnerNumberModel(2, 1, 10080, 1));
    private final JComboBox<ChronoUnit> customUnit = new JComboBox<>(
            new ChronoUnit[]{ChronoUnit.MINUTES, ChronoUnit.HOURS, ChronoUnit.DAYS});
    private final JTextArea url = new JTextArea(5, 50);
    private final JLabel expires = new JLabel(" ");
    private final JButton copy = new JButton("Copy to Clipboard");

    public static void show(Component parent, AppContext ctx, S3Connection connection, S3Path path) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        new PresignedUrlDialog(owner, ctx, connection, path).setVisible(true);
    }

    private PresignedUrlDialog(Window owner, AppContext ctx, S3Connection connection, S3Path path) {
        super(owner, "Presigned URL", ModalityType.APPLICATION_MODAL);
        expiry.setSelectedItem(Expiry.HOUR_1);
        customUnit.setSelectedItem(ChronoUnit.HOURS);
        JPanel custom = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        custom.add(customValue);
        custom.add(customUnit);
        expiry.addActionListener(e -> custom.setVisible(expiry.getSelectedItem() == Expiry.CUSTOM));
        custom.setVisible(false);

        url.setLineWrap(true);
        url.setEditable(false);
        url.setWrapStyleWord(false);
        copy.setEnabled(false);
        copy.addActionListener(e -> {
            Dialogs.copyToClipboard(url.getText());
            copy.setText("Copied");
        });

        JButton generate = new JButton("Generate");
        JButton close = new JButton("Close");
        generate.addActionListener(e -> {
            Duration d = selectedDuration();
            if (d.compareTo(PresignedUrlService.MAX_DURATION) > 0) {
                Dialogs.error(this, "Invalid expiration", "Presigned URLs can be valid for at most 7 days.");
                return;
            }
            generate.setEnabled(false);
            Background.run(() -> ctx.presigner.presign(connection, path, d), presigned -> {
                generate.setEnabled(true);
                url.setText(presigned.url());
                url.setCaretPosition(0);
                expires.setText("Expires " + DateTimeUtils.formatPrecise(presigned.expiresAt()));
                copy.setEnabled(true);
                copy.setText("Copy to Clipboard");
            }, error -> {
                generate.setEnabled(true);
                Dialogs.error(this, error, "read this object");
            });
        });
        close.addActionListener(e -> dispose());

        FormPanel form = new FormPanel()
                .row("Object:", new JLabel(path.toUri()))
                .row("Valid for:", expiry)
                .row("", custom)
                .row("URL:", new JScrollPane(url))
                .row("", expires)
                .wide(new JLabel("<html><div style='width:420px'>Anyone with this URL can download the object "
                        + "until it expires. The URL is signed with this connection's credentials and stops "
                        + "working early if those are temporary and expire.</div></html>"));

        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(generate, copy, close), BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, generate);
        pack();
        setLocationRelativeTo(owner);
    }

    private Duration selectedDuration() {
        Expiry e = (Expiry) expiry.getSelectedItem();
        if (e != null && e.duration != null) {
            return e.duration;
        }
        ChronoUnit unit = (ChronoUnit) customUnit.getSelectedItem();
        return Duration.of((Integer) customValue.getValue(), unit == null ? ChronoUnit.HOURS : unit);
    }
}
