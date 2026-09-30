package com.s3explorer.ui.dialog;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/** Small helpers shared by dialogs. */
final class DialogSupport {

    private DialogSupport() {
    }

    static JPanel buttonBar(JButton... buttons) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        for (JButton b : buttons) {
            bar.add(b);
        }
        return bar;
    }

    /** Escape closes the dialog; {@code defaultButton} responds to Enter. */
    static void install(JDialog dialog, JButton defaultButton) {
        dialog.getRootPane().registerKeyboardAction(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (defaultButton != null) {
            dialog.getRootPane().setDefaultButton(defaultButton);
        }
    }
}
