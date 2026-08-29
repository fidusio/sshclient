package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.config.ConnectionConfig;
import io.xlogistx.jssh.config.ConnectionManager;
import io.xlogistx.jssh.config.JSSHConst;
import io.xlogistx.jssh.ssh.SSHConnection;
import org.apache.sshd.client.channel.ChannelShell;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.List;

/**
 * Connection dialog for SSH settings with profile management
 */
public class ConnectDialog extends JDialog {

    private JComboBox<String> profileCombo;
    private JTextField hostField;
    private JSpinner portSpinner;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JCheckBox useKeyAuth;
    private JTextField keyFileField;
    private JPasswordField passphraseField;
    private JComboBox<String> termTypeCombo;
    private JComboBox<String> lineEndingCombo;   // paste newline: AUTO / LF / CR / CRLF
    private JSpinner colsSpinner;
    private JSpinner rowsSpinner;
    private JCheckBox x11ForwardingCheckbox;
    private JTextField x11DisplayField;

    private JButton connectBtn;
    private JProgressBar progressBar;
    private JLabel progressLabel;

    private ConnectionManager connectionManager;
    private boolean connected = false;
    private MainFrame.SessionTab sessionTab;
    private boolean loadingProfile = false;

    // Connect-in-flight state. 'connecting' is only touched on the EDT;
    // 'cancelled' is read by the connect thread.
    private boolean connecting = false;
    private volatile boolean cancelled = false;
    // The connection being built by the connect thread, so Cancel can abort it
    private volatile SSHConnection inFlightConn;
    // Dialog-owned copy of the credentials for the in-flight connect; wiped on
    // cancel/close and once the SessionTab has taken its own copies
    private MainFrame.SessionSpec pendingSpec;

    public ConnectDialog(Frame owner) {
        super(owner, "Connect to SSH Server", true);
        connectionManager = ConnectionManager.getInstance();
        initUI();
        pack();
        setMinimumSize(new Dimension(JSSHConst.CONNECT_DIALOG_MIN_WIDTH, JSSHConst.CONNECT_DIALOG_MIN_HEIGHT));
        setLocationRelativeTo(owner);

        // Closing the window is a cancel: a connect still in flight must not
        // turn into an orphaned session
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Profile selection panel at top
        JPanel profilePanel = createProfilePanel();
        add(profilePanel, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();

        // Connection tab
        JPanel connPanel = createConnectionPanel();
        tabs.addTab("Connection", connPanel);

        // Terminal tab
        JPanel termPanel = createTerminalPanel();
        tabs.addTab("Terminal", termPanel);

        add(tabs, BorderLayout.CENTER);

        // Buttons, with a progress indicator on the left while connecting
        JPanel southPanel = new JPanel(new BorderLayout());

        JPanel progressPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(100, 16));
        progressBar.setVisible(false);
        progressPanel.add(progressBar);
        progressLabel = new JLabel("");
        progressLabel.setVisible(false);
        progressPanel.add(progressLabel);
        southPanel.add(progressPanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> connect());
        buttonPanel.add(connectBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> cancel());
        buttonPanel.add(cancelBtn);

        southPanel.add(buttonPanel, BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        // Enter key connects
        getRootPane().setDefaultButton(connectBtn);
    }

    private JPanel createProfilePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Saved Connections"));

        JPanel comboPanel = new JPanel(new BorderLayout(5, 0));

        profileCombo = new JComboBox<>();
        profileCombo.setEditable(false);
        refreshProfiles();
        profileCombo.addActionListener(e -> {
            if (!loadingProfile) {
                loadSelectedProfile();
            }
        });
        comboPanel.add(profileCombo, BorderLayout.CENTER);

        // Profile buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));

        JButton saveBtn = new JButton("Save");
        saveBtn.setToolTipText("Save current settings as profile");
        saveBtn.setMargin(new Insets(2, 8, 2, 8));
        saveBtn.addActionListener(e -> saveProfile());
        btnPanel.add(saveBtn);

        JButton saveAsBtn = new JButton("Save As...");
        saveAsBtn.setToolTipText("Save as new profile");
        saveAsBtn.setMargin(new Insets(2, 8, 2, 8));
        saveAsBtn.addActionListener(e -> saveProfileAs());
        btnPanel.add(saveAsBtn);

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.setToolTipText("Delete selected profile");
        deleteBtn.setMargin(new Insets(2, 8, 2, 8));
        deleteBtn.addActionListener(e -> deleteProfile());
        btnPanel.add(deleteBtn);

        comboPanel.add(btnPanel, BorderLayout.EAST);
        panel.add(comboPanel, BorderLayout.CENTER);

        return panel;
    }

    private void refreshProfiles() {
        loadingProfile = true;
        String selected = (String) profileCombo.getSelectedItem();
        profileCombo.removeAllItems();
        profileCombo.addItem(JSSHConst.NEW_CONNECTION_LABEL);

        List<String> names = connectionManager.getConnectionNames();
        for (String name : names) {
            profileCombo.addItem(name);
        }

        if (selected != null && names.contains(selected)) {
            profileCombo.setSelectedItem(selected);
        }
        loadingProfile = false;
    }

    private void loadSelectedProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null || selected.equals(JSSHConst.NEW_CONNECTION_LABEL)) {
            clearFields();
            return;
        }

        ConnectionConfig config = connectionManager.get(selected);
        if (config != null) {
            loadConfig(config);
        }
    }

    private void loadConfig(ConnectionConfig config) {
        loadingProfile = true;

        hostField.setText(config.getHost() != null ? config.getHost() : "");
        portSpinner.setValue(config.getPort());
        usernameField.setText(config.getUsername() != null ? config.getUsername() : "");
        useKeyAuth.setSelected(config.isUseKeyAuth());
        keyFileField.setText(config.getKeyFile() != null ? config.getKeyFile() : "");
        termTypeCombo.setSelectedItem(config.getTerminalType());
        lineEndingCombo.setSelectedItem(config.getPasteLineEnding());
        colsSpinner.setValue(config.getColumns());
        rowsSpinner.setValue(config.getRows());
        x11ForwardingCheckbox.setSelected(config.isX11Forwarding());
        x11DisplayField.setText(config.getX11Display() != null ? config.getX11Display() : JSSHConst.DEFAULT_X11_DISPLAY);

        updateKeyFields();
        updateX11Fields();

        loadingProfile = false;
    }

    private void clearFields() {
        loadingProfile = true;

        hostField.setText("");
        portSpinner.setValue(JSSHConst.DEFAULT_SSH_PORT);
        usernameField.setText(System.getProperty("user.name"));
        passwordField.setText("");
        useKeyAuth.setSelected(false);
        keyFileField.setText(getDefaultKeyFile());
        passphraseField.setText("");
        termTypeCombo.setSelectedItem(JSSHConst.DEFAULT_TERMINAL_TYPE);
        lineEndingCombo.setSelectedItem(JSSHConst.PASTE_LINE_ENDING_AUTO);
        colsSpinner.setValue(JSSHConst.DEFAULT_TERMINAL_COLS);
        rowsSpinner.setValue(JSSHConst.DEFAULT_TERMINAL_ROWS);
        x11ForwardingCheckbox.setSelected(false);
        x11DisplayField.setText(System.getenv("DISPLAY") != null ? System.getenv("DISPLAY") : JSSHConst.DEFAULT_X11_DISPLAY);

        updateKeyFields();
        updateX11Fields();

        loadingProfile = false;
    }

    private ConnectionConfig createConfigFromFields() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(hostField.getText().trim());
        config.setPort((Integer) portSpinner.getValue());
        config.setUsername(usernameField.getText().trim());
        config.setUseKeyAuth(useKeyAuth.isSelected());
        config.setKeyFile(keyFileField.getText().trim());
        config.setTerminalType((String) termTypeCombo.getSelectedItem());
        config.setPasteLineEnding((String) lineEndingCombo.getSelectedItem());
        config.setColumns((Integer) colsSpinner.getValue());
        config.setRows((Integer) rowsSpinner.getValue());
        config.setX11Forwarding(x11ForwardingCheckbox.isSelected());
        config.setX11Display(x11DisplayField.getText().trim());
        return config;
    }

    private void saveProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null || selected.equals(JSSHConst.NEW_CONNECTION_LABEL)) {
            saveProfileAs();
            return;
        }

        try {
            ConnectionConfig config = createConfigFromFields();
            config.setName(selected);
            connectionManager.save(config);
            JOptionPane.showMessageDialog(this, "Profile saved: " + selected,
                    "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to save profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveProfileAs() {
        String host = hostField.getText().trim();
        String user = usernameField.getText().trim();
        String defaultName = "";
        if (!host.isEmpty()) {
            defaultName = (user.isEmpty() ? "" : user + "@") + host;
        }

        String name = JOptionPane.showInputDialog(this, "Profile name:", defaultName);
        if (name == null || name.trim().isEmpty()) {
            return;
        }

        name = name.trim();

        if (connectionManager.exists(name)) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Profile '" + name + "' already exists. Overwrite?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try {
            ConnectionConfig config = createConfigFromFields();
            config.setName(name);
            connectionManager.save(config);
            refreshProfiles();
            profileCombo.setSelectedItem(name);
            JOptionPane.showMessageDialog(this, "Profile saved: " + name,
                    "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to save profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null || selected.equals(JSSHConst.NEW_CONNECTION_LABEL)) {
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
                "Delete profile '" + selected + "'?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            try {
                connectionManager.delete(selected);
                refreshProfiles();
                profileCombo.setSelectedItem(JSSHConst.NEW_CONNECTION_LABEL);
                clearFields();
            } catch (IOException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Failed to delete profile: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String getDefaultKeyFile() {
        String homeDir = System.getProperty("user.home");
        File defaultKey = new File(homeDir, JSSHConst.DEFAULT_KEY_ED25519);
        if (!defaultKey.exists()) {
            defaultKey = new File(homeDir, JSSHConst.DEFAULT_KEY_RSA);
        }
        return defaultKey.exists() ? defaultKey.getAbsolutePath() : "";
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Host
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Host:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        hostField = new JTextField(20);
        panel.add(hostField, gbc);

        // Port
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Port:"), gbc);

        gbc.gridx = 3;
        portSpinner = new JSpinner(new SpinnerNumberModel(JSSHConst.DEFAULT_SSH_PORT, JSSHConst.MIN_PORT, JSSHConst.MAX_PORT, 1));
        // Ports are not amounts - no digit grouping (8080, not 8,080)
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#"));
        panel.add(portSpinner, gbc);

        // Username
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Username:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        usernameField = new JTextField(System.getProperty("user.name"));
        panel.add(usernameField, gbc);

        // Password
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Password:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        passwordField = new JPasswordField();
        panel.add(passwordField, gbc);

        // Key authentication section
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 4;
        useKeyAuth = new JCheckBox("Use public key authentication");
        useKeyAuth.addActionListener(e -> updateKeyFields());
        panel.add(useKeyAuth, gbc);

        // Key file
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Key file:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        keyFileField = new JTextField();
        keyFileField.setEnabled(false);
        keyFileField.setText(getDefaultKeyFile());
        panel.add(keyFileField, gbc);

        gbc.gridx = 3;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        JButton browseBtn = new JButton("...");
        browseBtn.setEnabled(false);
        browseBtn.addActionListener(e -> browseKeyFile());
        panel.add(browseBtn, gbc);

        // Passphrase
        gbc.gridx = 0;
        gbc.gridy = 5;
        panel.add(new JLabel("Passphrase:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        passphraseField = new JPasswordField();
        passphraseField.setEnabled(false);
        panel.add(passphraseField, gbc);

        // Store references for enabling/disabling
        useKeyAuth.putClientProperty("browseBtn", browseBtn);

        return panel;
    }

    private JPanel createTerminalPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Terminal type
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Terminal type:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        termTypeCombo = new JComboBox<>(JSSHConst.TERMINAL_TYPES);
        panel.add(termTypeCombo, gbc);

        // Paste newline (same row, to the right)
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Paste newline:"), gbc);

        gbc.gridx = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        lineEndingCombo = new JComboBox<>(JSSHConst.PASTE_LINE_ENDINGS);
        lineEndingCombo.setToolTipText("Line ending sent for pasted text: AUTO detects from the server "
                + "(LF for Unix-like hosts, CR for Windows OpenSSH)");
        panel.add(lineEndingCombo, gbc);

        // Size
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Columns:"), gbc);

        gbc.gridx = 1;
        colsSpinner = new JSpinner(new SpinnerNumberModel(JSSHConst.DEFAULT_TERMINAL_COLS, JSSHConst.MIN_TERMINAL_COLS, JSSHConst.MAX_TERMINAL_COLS, 1));
        panel.add(colsSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(new JLabel("Rows:"), gbc);

        gbc.gridx = 1;
        rowsSpinner = new JSpinner(new SpinnerNumberModel(JSSHConst.DEFAULT_TERMINAL_ROWS, JSSHConst.MIN_TERMINAL_ROWS, JSSHConst.MAX_TERMINAL_ROWS, 1));
        panel.add(rowsSpinner, gbc);

        // X11 Forwarding section
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        panel.add(new JSeparator(), gbc);

        gbc.gridy = 4;
        gbc.gridwidth = 1;
        x11ForwardingCheckbox = new JCheckBox("Enable X11 Forwarding");
        x11ForwardingCheckbox.setToolTipText("Forward X11 graphical applications to local display");
        x11ForwardingCheckbox.addActionListener(e -> updateX11Fields());
        panel.add(x11ForwardingCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        panel.add(new JLabel("X11 Display:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        x11DisplayField = new JTextField(JSSHConst.DEFAULT_X11_DISPLAY);
        x11DisplayField.setEnabled(false);
        x11DisplayField.setToolTipText("X11 display (e.g., localhost:0, :0, or IP:display)");

        // Try to get default from DISPLAY environment
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            x11DisplayField.setText(display);
        }
        panel.add(x11DisplayField, gbc);

        // X11 availability note
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        JLabel x11Note = new JLabel();
        if (SSHConnection.isX11Available()) {
            x11Note.setText("<html><font color='green'>X11 display detected</font></html>");
        } else {
            x11Note.setText("<html><font color='gray'>No X11 display detected (set DISPLAY or run X server)</font></html>");
        }
        x11Note.setFont(x11Note.getFont().deriveFont(Font.ITALIC, 10f));
        panel.add(x11Note, gbc);

        return panel;
    }

    private void updateX11Fields() {
        x11DisplayField.setEnabled(x11ForwardingCheckbox.isSelected());
    }

    private void updateKeyFields() {
        boolean useKey = useKeyAuth.isSelected();
        keyFileField.setEnabled(useKey);
        passphraseField.setEnabled(useKey);
        passwordField.setEnabled(!useKey);

        JButton browseBtn = (JButton) useKeyAuth.getClientProperty("browseBtn");
        if (browseBtn != null) {
            browseBtn.setEnabled(useKey);
        }
    }

    private void browseKeyFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(new File(System.getProperty("user.home"), ".ssh"));
        chooser.setFileHidingEnabled(false);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            keyFileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void connect() {
        if (connecting) {
            // A connect is already in flight - ignore the second click
            return;
        }

        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        String username = usernameField.getText().trim();

        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a host", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a username", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get password/key info before connecting - kept as char[] so they can
        // be wiped once the session tab has taken its own copies
        final char[] password;
        final String keyFile;
        final char[] passphrase;
        final boolean useKey = useKeyAuth.isSelected();

        if (useKey) {
            keyFile = keyFileField.getText().trim();
            passphrase = passphraseField.getPassword();
            password = null;
            if (keyFile.isEmpty()) {
                java.util.Arrays.fill(passphrase, '\0');
                JOptionPane.showMessageDialog(this, "Please select a key file", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            password = passwordField.getPassword();
            keyFile = null;
            passphrase = null;
            if (password.length == 0) {
                JOptionPane.showMessageDialog(this, "Please enter a password", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        // Snapshot the terminal / X11 fields here, on the EDT - the connect
        // thread must not read Swing components
        int cols = (Integer) colsSpinner.getValue();
        int rows = (Integer) rowsSpinner.getValue();
        String termType = (String) termTypeCombo.getSelectedItem();
        String pasteLineEnding = (String) lineEndingCombo.getSelectedItem();
        boolean enableX11 = x11ForwardingCheckbox.isSelected();
        String x11Display = x11DisplayField.getText().trim();

        String x11Host = null;
        int x11DisplayNum = 0;
        if (enableX11 && !x11Display.isEmpty()) {
            // Parse display string (format: [host]:display[.screen])
            int colonIdx = x11Display.lastIndexOf(':');
            if (colonIdx >= 0) {
                x11Host = colonIdx > 0 ? x11Display.substring(0, colonIdx) : "localhost";
                try {
                    String dispNum = x11Display.substring(colonIdx + 1);
                    int dotIdx = dispNum.indexOf('.');
                    if (dotIdx > 0) {
                        dispNum = dispNum.substring(0, dotIdx);
                    }
                    x11DisplayNum = Integer.parseInt(dispNum);
                } catch (NumberFormatException ex) {
                    ex.printStackTrace();
                    x11DisplayNum = 0;
                }
            }
        }

        final MainFrame.SessionSpec spec = new MainFrame.SessionSpec(host, port, username, password, keyFile, passphrase,
                termType, cols, rows, enableX11, x11Host, x11DisplayNum, pasteLineEnding);
        // The spec has its own copies - the field arrays are not needed any more
        if (password != null) java.util.Arrays.fill(password, '\0');
        if (passphrase != null) java.util.Arrays.fill(passphrase, '\0');
        pendingSpec = spec;

        setConnecting(true, "Connecting to " + host + (port != JSSHConst.DEFAULT_SSH_PORT ? ":" + port : "") + "...");

        // Run connection in background thread. It touches no Swing state; the
        // result is handed back to the EDT via onConnected / onConnectFailed.
        final String title = username + "@" + host;
        Thread connectThread = new Thread(() -> {
            SSHConnection conn = null;
            try {
                conn = new SSHConnection();
                inFlightConn = conn;

                // Host key verification with known hosts support (SSHConnection
                // marshals the verifier's dialogs onto the EDT)
                conn.setHostKeyVerifier(MainFrame.createHostKeyVerifier(this));

                // Connect (includes host key verification)
                conn.connect(spec.host, spec.port, JSSHConst.CONNECTION_TIMEOUT_MS);
                if (cancelled) {
                    throw new IOException("Cancelled");
                }

                // Now authenticate (separate from connect)
                boolean authenticated;
                if (spec.isKeyAuth()) {
                    authenticated = conn.authenticatePublicKey(spec.username, spec.keyFile, spec.passphrase,
                            JSSHConst.AUTH_TIMEOUT_MS);
                } else {
                    authenticated = conn.authenticatePassword(spec.username, spec.password,
                            JSSHConst.AUTH_TIMEOUT_MS);
                }

                if (!authenticated) {
                    throw new IOException("Authentication failed - check username/password");
                }
                if (cancelled) {
                    throw new IOException("Cancelled");
                }

                // Open shell with X11 forwarding if enabled
                ChannelShell shell = conn.openShell(spec.termType, spec.cols, spec.rows,
                        spec.x11Forwarding, spec.x11Host, spec.x11DisplayNum);

                final SSHConnection successConn = conn;
                SwingUtilities.invokeLater(() -> onConnected(successConn, shell, spec, title));

            } catch (Exception e) {
                if (!cancelled) {
                    e.printStackTrace();
                }
                final SSHConnection failedConn = conn;
                final String errorMsg = e.getMessage();
                SwingUtilities.invokeLater(() -> onConnectFailed(failedConn, errorMsg));
            } finally {
                inFlightConn = null;
            }
        }, "jssh-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * Connect thread succeeded (EDT). If the user cancelled or closed the dialog
     * meanwhile, the session is closed and discarded rather than orphaned.
     */
    private void onConnected(SSHConnection conn, ChannelShell shell, MainFrame.SessionSpec spec, String title) {
        if (cancelled) {
            conn.close();
            spec.wipe();
            if (pendingSpec == spec) {
                pendingSpec = null;
            }
            connecting = false;
            return;
        }

        try {
            // Builds the TerminalPanel + SessionTab on the EDT and starts the reader thread
            sessionTab = MainFrame.attachShell(conn, shell, spec, title, null);
            connected = true;
        } catch (RuntimeException e) {
            onConnectFailed(conn, e.getMessage());
            return;
        } finally {
            // The session tab keeps its own copies of the credentials
            spec.wipe();
            if (pendingSpec == spec) {
                pendingSpec = null;
            }
        }

        setConnecting(false, null);
        dispose();
    }

    /**
     * Connect thread failed (EDT). Silent if the dialog was already cancelled.
     */
    private void onConnectFailed(SSHConnection conn, String errorMsg) {
        if (conn != null) {
            conn.close();
        }
        if (pendingSpec != null) {
            pendingSpec.wipe();
            pendingSpec = null;
        }
        if (cancelled) {
            connecting = false;
            return;
        }
        setConnecting(false, null);
        JOptionPane.showMessageDialog(ConnectDialog.this,
                "Connection failed: " + errorMsg,
                "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Toggle the "connect in flight" UI: Connect disabled, progress shown.
     */
    private void setConnecting(boolean inFlight, String message) {
        connecting = inFlight;
        connectBtn.setEnabled(!inFlight);
        progressBar.setVisible(inFlight);
        progressLabel.setText(inFlight && message != null ? message : "");
        progressLabel.setVisible(inFlight);
        setCursor(inFlight ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /**
     * Cancel button / window close. Any connect still in flight will close its
     * connection when it completes (see {@link #onConnected}).
     */
    private void cancel() {
        cancelled = true;
        // Abort an in-flight connect instead of letting it run to its timeout:
        // closing the client fails the pending connect/auth on the connect
        // thread, which then reports (silently, since cancelled) and cleans up.
        SSHConnection conn = inFlightConn;
        if (conn != null) {
            Thread abort = new Thread(conn::close, "jssh-connect-abort");
            abort.setDaemon(true);
            abort.start();
        }
        dispose();
    }

    @Override
    public void dispose() {
        // Wipe the dialog's own credential copies. While a connect is still in
        // flight the arrays are in use by the connect thread; onConnected /
        // onConnectFailed (which always run, even after dispose) wipe them then.
        if (!connecting && pendingSpec != null) {
            pendingSpec.wipe();
            pendingSpec = null;
        }
        super.dispose();
    }

    /**
     * Pre-fill fields from a config (for quick connect)
     */
    public void setConfig(ConnectionConfig config) {
        if (config != null) {
            loadConfig(config);
        }
    }

    /**
     * Pre-fill host/port/user for quick connect
     */
    public void setQuickConnect(String host, int port, String user) {
        if (host != null) hostField.setText(host);
        if (port > 0) portSpinner.setValue(port);
        if (user != null) usernameField.setText(user);
    }

    public boolean isConnected() {
        return connected;
    }

    public MainFrame.SessionTab getSessionTab() {
        return sessionTab;
    }
}
