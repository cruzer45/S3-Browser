package com.s3explorer.ui.dialog;

import com.s3explorer.app.AppContext;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AuthMethod;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.ui.Dialogs;
import com.s3explorer.ui.FormPanel;
import com.s3explorer.ui.Icons;
import com.s3explorer.util.Background;
import com.s3explorer.util.ErrorTranslator;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Arrays;
import java.util.List;

/**
 * Connection manager: lists saved profiles and edits the selected one. Returns the ID of the
 * profile to connect to, or null.
 */
public final class ConnectionDialog extends JDialog {

    /** Presets that fill in endpoint, region and addressing for common providers. */
    private enum Provider {
        AWS("Amazon S3", "", "us-east-1", false),
        MINIO("MinIO", "http://localhost:9000", "us-east-1", true),
        R2("Cloudflare R2", "https://<account-id>.r2.cloudflarestorage.com", "auto", true),
        WASABI("Wasabi", "https://s3.wasabisys.com", "us-east-1", false),
        B2("Backblaze B2", "https://s3.us-west-004.backblazeb2.com", "us-west-004", false),
        SPACES("DigitalOcean Spaces", "https://nyc3.digitaloceanspaces.com", "nyc3", false),
        CEPH("Ceph / other S3-compatible", "https://", "us-east-1", true);

        final String label;
        final String endpoint;
        final String region;
        final boolean pathStyle;

        Provider(String label, String endpoint, String region, boolean pathStyle) {
            this.label = label;
            this.endpoint = endpoint;
            this.region = region;
            this.pathStyle = pathStyle;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext ctx;
    private final DefaultListModel<ConnectionProfile> listModel = new DefaultListModel<>();
    private final JList<ConnectionProfile> list = new JList<>(listModel);

    private final JTextField name = new JTextField(24);
    private final JComboBox<Provider> provider = new JComboBox<>(Provider.values());
    private final JTextField endpoint = new JTextField();
    private final JTextField region = new JTextField();
    private final JComboBox<AuthMethod> auth = new JComboBox<>(AuthMethod.values());
    private final JComboBox<String> awsProfile = new JComboBox<>();
    private final JTextField accessKey = new JTextField();
    private final JPasswordField secretKey = new JPasswordField();
    private final JPasswordField sessionToken = new JPasswordField();
    private final JCheckBox rememberSecret = new JCheckBox("Remember secret key (stored in a file readable only by you)");
    private final JCheckBox pathStyle = new JCheckBox("Use path-style addressing (endpoint/bucket/key)");
    private final JSpinner connectTimeout = new JSpinner(new SpinnerNumberModel(0, 0, 600, 1));
    private final JSpinner requestTimeout = new JSpinner(new SpinnerNumberModel(0, 0, 3600, 5));
    private final JTextField pinnedBuckets = new JTextField();
    private final JLabel testResult = new JLabel(" ");
    private final JPanel form;
    private final JButton save = new JButton("Save");
    private final JButton connect = new JButton("Connect");

    private ConnectionProfile editing;
    private boolean loadingForm;
    private String result;

    public static String show(Component parent, AppContext ctx, String selectedId) {
        Window owner = parent instanceof Window w ? w : javax.swing.SwingUtilities.getWindowAncestor(parent);
        ConnectionDialog dialog = new ConnectionDialog(owner, ctx, selectedId);
        dialog.setVisible(true);
        return dialog.result;
    }

    private ConnectionDialog(Window owner, AppContext ctx, String selectedId) {
        super(owner, "Connections", ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;

        awsProfile.setEditable(true);
        ctx.credentials.awsProfileNames().forEach(awsProfile::addItem);
        for (JTextField f : new JTextField[]{endpoint, region, accessKey, pinnedBuckets}) {
            f.setColumns(28);
        }
        endpoint.putClientProperty("JTextField.placeholderText", "Leave empty for Amazon S3");
        region.putClientProperty("JTextField.placeholderText", "us-east-1");
        pinnedBuckets.putClientProperty("JTextField.placeholderText", "bucket-a, bucket-b (optional)");
        pinnedBuckets.setToolTipText("Buckets to show when the credentials are not allowed to list all buckets.");
        connectTimeout.setToolTipText("0 uses the value from Settings");
        requestTimeout.setToolTipText("0 uses the value from Settings");

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                setIcon(Icons.get("connection"));
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ConnectionProfile p = list.getSelectedValue();
                if (p != null && (editing == null || !p.id.equals(editing.id))) {
                    loadForm(p);
                }
            }
        });

        JButton add = new JButton("New");
        JButton duplicate = new JButton("Duplicate");
        JButton delete = new JButton("Delete");
        add.addActionListener(e -> newProfile());
        duplicate.addActionListener(e -> duplicateProfile());
        delete.addActionListener(e -> deleteProfile());
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        listButtons.add(add);
        listButtons.add(duplicate);
        listButtons.add(delete);
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(290, 400));

        form = new FormPanel()
                .section("Connection")
                .row("Name:", name)
                .row("Provider:", provider)
                .row("Endpoint URL:", endpoint)
                .row("Region:", region)
                .wide(pathStyle)
                .section("Authentication")
                .row("Method:", auth)
                .row("AWS profile:", awsProfile)
                .row("Access key ID:", accessKey)
                .row("Secret access key:", secretKey)
                .row("Session token:", sessionToken)
                .wide(rememberSecret)
                .section("Advanced")
                .row("Connect timeout (s):", connectTimeout)
                .row("Read timeout (s):", requestTimeout)
                .row("Pinned buckets:", pinnedBuckets)
                .wide(testResult)
                .fillRemaining();

        provider.addActionListener(e -> applyProvider());
        auth.addActionListener(e -> updateAuthFields());

        JButton test = new JButton("Test Connection");
        JButton close = new JButton("Close");
        test.addActionListener(e -> testConnection());
        save.addActionListener(e -> saveCurrent(true));
        connect.addActionListener(e -> {
            if (saveCurrent(false)) {
                result = editing.id;
                dispose();
            }
        });
        close.addActionListener(e -> dispose());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(form));
        split.setDividerLocation(290);
        split.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JPanel content = new JPanel(new BorderLayout());
        content.add(split, BorderLayout.CENTER);
        content.add(DialogSupport.buttonBar(test, save, connect, close), BorderLayout.SOUTH);
        setContentPane(content);
        DialogSupport.install(this, connect);

        reloadList(selectedId);
        if (listModel.isEmpty()) {
            newProfile();
        }
        setSize(920, 640);
        setLocationRelativeTo(owner);
    }

    private void reloadList(String selectId) {
        listModel.clear();
        List<ConnectionProfile> profiles = ctx.connections.profiles();
        profiles.forEach(listModel::addElement);
        int index = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(selectId)) {
                index = i;
            }
        }
        if (!profiles.isEmpty()) {
            editing = null;
            list.setSelectedIndex(index);
        }
    }

    private void newProfile() {
        ConnectionProfile p = new ConnectionProfile();
        p.name = "New connection";
        p.region = ctx.settings.get().s3.defaultRegion;
        p.endpoint = ctx.settings.get().s3.defaultEndpoint;
        p.pathStyleAccess = ctx.settings.get().s3.pathStyleAccess;
        list.clearSelection();
        loadForm(p);
        name.requestFocusInWindow();
        name.selectAll();
    }

    private void duplicateProfile() {
        if (editing == null) {
            return;
        }
        ConnectionProfile copy = readForm();
        copy.id = java.util.UUID.randomUUID().toString();
        copy.name = copy.name + " (copy)";
        list.clearSelection();
        loadForm(copy);
    }

    private void deleteProfile() {
        ConnectionProfile p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        if (Dialogs.confirm(this, "Delete Connection", "Delete the connection \"" + p.name + "\"?", "Delete")) {
            ctx.connections.deleteProfile(p.id);
            editing = null;
            reloadList(null);
            if (listModel.isEmpty()) {
                newProfile();
            }
        }
    }

    private void loadForm(ConnectionProfile p) {
        loadingForm = true;
        editing = p;
        name.setText(p.name);
        endpoint.setText(p.endpoint);
        region.setText(p.region);
        auth.setSelectedItem(p.authMethod);
        awsProfile.setSelectedItem(p.awsProfile == null || p.awsProfile.isBlank() ? "default" : p.awsProfile);
        accessKey.setText(p.accessKeyId);
        secretKey.setText(p.secretAccessKey == null ? "" : p.secretAccessKey);
        sessionToken.setText(p.sessionToken == null ? "" : p.sessionToken);
        rememberSecret.setSelected(p.rememberSecret);
        pathStyle.setSelected(p.pathStyleAccess);
        connectTimeout.setValue(p.connectTimeoutSeconds);
        requestTimeout.setValue(p.requestTimeoutSeconds);
        pinnedBuckets.setText(String.join(", ", p.pinnedBuckets == null ? List.of() : p.pinnedBuckets));
        provider.setSelectedItem(guessProvider(p));
        testResult.setText(" ");
        updateAuthFields();
        loadingForm = false;
    }

    private Provider guessProvider(ConnectionProfile p) {
        String e = p.endpoint == null ? "" : p.endpoint;
        if (e.isBlank() || p.isAwsEndpoint()) return Provider.AWS;
        if (e.contains("r2.cloudflarestorage.com")) return Provider.R2;
        if (e.contains("wasabisys.com")) return Provider.WASABI;
        if (e.contains("backblazeb2.com")) return Provider.B2;
        if (e.contains("digitaloceanspaces.com")) return Provider.SPACES;
        if (e.contains(":9000")) return Provider.MINIO;
        return Provider.CEPH;
    }

    private void applyProvider() {
        if (loadingForm) {
            return;
        }
        Provider p = (Provider) provider.getSelectedItem();
        if (p == null) {
            return;
        }
        endpoint.setText(p.endpoint);
        region.setText(p.region);
        pathStyle.setSelected(p.pathStyle);
        if (p != Provider.AWS && auth.getSelectedItem() == AuthMethod.DEFAULT_CHAIN) {
            auth.setSelectedItem(AuthMethod.ACCESS_KEY);
        }
    }

    private void updateAuthFields() {
        AuthMethod m = (AuthMethod) auth.getSelectedItem();
        boolean keys = m == AuthMethod.ACCESS_KEY;
        awsProfile.setEnabled(m == AuthMethod.PROFILE);
        accessKey.setEnabled(keys);
        secretKey.setEnabled(keys);
        sessionToken.setEnabled(keys);
        rememberSecret.setEnabled(keys);
    }

    private ConnectionProfile readForm() {
        ConnectionProfile p = editing == null ? new ConnectionProfile() : editing.copy();
        p.name = name.getText().trim();
        p.endpoint = endpoint.getText().trim();
        p.region = region.getText().trim();
        p.authMethod = (AuthMethod) auth.getSelectedItem();
        Object profileName = awsProfile.getEditor().getItem();
        p.awsProfile = profileName == null ? "" : profileName.toString().trim();
        p.accessKeyId = accessKey.getText().trim();
        String secret = new String(secretKey.getPassword());
        String token = new String(sessionToken.getPassword());
        p.secretAccessKey = secret.isEmpty() ? null : secret;
        p.sessionToken = token.isBlank() ? null : token.trim();
        p.rememberSecret = rememberSecret.isSelected();
        p.pathStyleAccess = pathStyle.isSelected();
        p.connectTimeoutSeconds = (Integer) connectTimeout.getValue();
        p.requestTimeoutSeconds = (Integer) requestTimeout.getValue();
        p.pinnedBuckets = Arrays.stream(pinnedBuckets.getText().split("[,\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (p.authMethod != AuthMethod.ACCESS_KEY) {
            p.secretAccessKey = null;
            p.sessionToken = null;
            p.rememberSecret = false;
        }
        return p;
    }

    private String validateForm(ConnectionProfile p) {
        if (p.name.isEmpty()) {
            return "Enter a name for the connection.";
        }
        if (!p.endpoint.isEmpty()) {
            try {
                java.net.URI uri = java.net.URI.create(p.endpoint.contains("://") ? p.endpoint : "https://" + p.endpoint);
                if (uri.getHost() == null || p.endpoint.contains("<")) {
                    return "The endpoint URL is not valid.";
                }
                if ("http".equalsIgnoreCase(uri.getScheme()) && !isLocal(uri.getHost())) {
                    if (!Dialogs.confirm(this, "Unencrypted endpoint",
                            "This endpoint uses plain HTTP, so data and signed requests travel unencrypted. "
                                    + "Use HTTPS for remote endpoints. Continue anyway?", "Continue")) {
                        return "";
                    }
                }
            } catch (IllegalArgumentException e) {
                return "The endpoint URL is not valid.";
            }
        }
        if (p.authMethod == AuthMethod.ACCESS_KEY && p.accessKeyId.isEmpty()) {
            return "Enter the access key ID.";
        }
        return null;
    }

    private static boolean isLocal(String host) {
        return host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.endsWith(".local") || host.equals("::1");
    }

    private boolean saveCurrent(boolean showConfirmation) {
        ConnectionProfile p = readForm();
        String problem = validateForm(p);
        if (problem != null) {
            if (!problem.isEmpty()) {
                Dialogs.error(this, "Check the connection", problem);
            }
            return false;
        }
        ctx.connections.saveProfile(p);
        editing = null;
        reloadList(p.id);
        if (showConfirmation) {
            testResult.setText("Saved.");
        }
        return true;
    }

    private void testConnection() {
        ConnectionProfile p = readForm();
        String problem = validateForm(p);
        if (problem != null) {
            if (!problem.isEmpty()) {
                Dialogs.error(this, "Check the connection", problem);
            }
            return;
        }
        if (p.authMethod == AuthMethod.ACCESS_KEY && !p.hasSecret()) {
            Dialogs.error(this, "Check the connection", "Enter the secret access key to test the connection.");
            return;
        }
        testResult.setIcon(null);
        testResult.setText("Testing…");
        Background.run(() -> {
            try (S3Connection temp = ctx.connections.openTemporary(p)) {
                return ctx.browser.listBuckets(temp).size();
            }
        }, count -> {
            testResult.setIcon(Icons.get("check"));
            testResult.setText("Connected successfully. " + count + " bucket(s) visible.");
        }, error -> {
            ErrorTranslator.UserMessage m = ErrorTranslator.translate(error, "list buckets");
            testResult.setIcon(Icons.get("cancel"));
            testResult.setText("<html>" + escape(m.title() + " " + m.message()) + "</html>");
        });
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
