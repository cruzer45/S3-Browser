package com.s3explorer.ui.dialog;

import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.ui.FormPanel;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import java.awt.Component;

/** Asks for a secret key that was not saved with the connection. */
public final class SecretPromptDialog {

    public record Result(String secretKey, String sessionToken, boolean remember) {
        @Override
        public String toString() {
            return "Result[remember=" + remember + "]";
        }
    }

    private SecretPromptDialog() {
    }

    public static Result show(Component parent, ConnectionProfile profile) {
        JPasswordField secret = new JPasswordField(28);
        JPasswordField token = new JPasswordField(28);
        JCheckBox remember = new JCheckBox("Remember secret key");
        FormPanel form = new FormPanel()
                .wide(new JLabel("Enter the secret key for \"" + profile.name + "\" (access key "
                        + profile.accessKeyId + ")."))
                .row("Secret access key:", secret)
                .row("Session token (optional):", token)
                .wide(new JLabel("Leave the session token empty for permanent keys (access keys starting with AKIA)."))
                .wide(remember);
        int choice = JOptionPane.showConfirmDialog(parent, form, "Credentials Required",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        String key = new String(secret.getPassword());
        if (choice != JOptionPane.OK_OPTION || key.isEmpty()) {
            return null;
        }
        String t = new String(token.getPassword()).trim();
        return new Result(key, t.isEmpty() ? null : t, remember.isSelected());
    }
}
