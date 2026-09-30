package com.s3explorer.controller;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.dialog.ConnectionDialog;
import com.s3explorer.ui.dialog.SecretPromptDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.util.Optional;

/** Connecting, switching and managing connection profiles. */
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);

    private final AppContext ctx;
    private final JFrame frame;
    private final BrowserController browser;

    public ConnectionController(AppContext ctx, JFrame frame, BrowserController browser) {
        this.ctx = ctx;
        this.frame = frame;
        this.browser = browser;
    }

    /** Connects to a saved profile, prompting for a secret key if one is needed. */
    public void connect(String profileId) {
        Optional<ConnectionProfile> found = ctx.connections.profile(profileId);
        if (found.isEmpty()) {
            return;
        }
        ConnectionProfile profile = found.get();
        if (ctx.connections.needsSecret(profile)) {
            SecretPromptDialog.Result secret = SecretPromptDialog.show(frame, profile);
            if (secret == null) {
                return;
            }
            ctx.connections.setSessionSecret(profile.id, secret.secretKey(), secret.sessionToken());
            if (secret.remember()) {
                profile.secretAccessKey = secret.secretKey();
                profile.sessionToken = secret.sessionToken();
                profile.rememberSecret = true;
                ctx.connections.saveProfile(profile);
            }
        }
        try {
            // Building the client is local work (no network); the bucket listing that follows
            // runs in the background and reports any authentication problem.
            S3Connection connection = ctx.connections.get(profileId);
            ctx.connections.setActive(connection);
            browser.setConnection(connection);
            log.info("Switched to connection '{}'", profile.name);
        } catch (RuntimeException e) {
            Dialogs.error(frame, e, "connect");
        }
    }

    public void disconnect() {
        ctx.connections.setActive(null);
        browser.setConnection(null);
    }

    /** Opens the connection manager; connects if the user chose "Connect". */
    public void openManager() {
        S3Connection active = ctx.connections.active();
        String chosen = ConnectionDialog.show(frame, ctx, active == null ? null : active.id());
        if (chosen != null) {
            connect(chosen);
        } else if (active != null && ctx.connections.profile(active.id()).isEmpty()) {
            disconnect();
        }
    }

    /** Connects to the last-used (or default) profile on startup, if configured. */
    public void restoreOnStartup() {
        var general = ctx.settings.get().general;
        String id = ctx.settings.get().s3.defaultConnectionId;
        if (general.restoreConnection && general.lastConnectionId != null) {
            id = general.lastConnectionId;
        }
        if (id != null && ctx.connections.profile(id).isPresent()) {
            connect(id);
        } else if (ctx.connections.profiles().isEmpty()) {
            openManager();
        }
    }
}
