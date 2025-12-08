package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.config.ConnectionConfig;
import io.xlogistx.jssh.config.ConnectionManager;
import io.xlogistx.jssh.ssh.SSHConnection;
import io.xlogistx.jssh.terminal.TerminalPanel;

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
    private JSpinner colsSpinner;
    private JSpinner rowsSpinner;
    private JCheckBox x11ForwardingCheckbox;
    private JTextField x11DisplayField;

    private ConnectionManager connectionManager;
    private boolean connected = false;
    private MainFrame.SessionTab sessionTab;
    private boolean loadingProfile = false;

    public ConnectDialog(Frame owner) {
        super(owner, "Connect to SSH Server", true);
        connectionManager = ConnectionManager.getInstance();
        initUI();
        pack();
        setMinimumSize(new Dimension(500, 400));
        setLocationRelativeTo(owner);
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

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> connect());
        buttonPanel.add(connectBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        buttonPanel.add(cancelBtn);

        add(buttonPanel, BorderLayout.SOUTH);

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
        profileCombo.addItem("<New Connection>");

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
        if (selected == null || selected.equals("<New Connection>")) {
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
        colsSpinner.setValue(config.getColumns());
        rowsSpinner.setValue(config.getRows());
        x11ForwardingCheckbox.setSelected(config.isX11Forwarding());
        x11DisplayField.setText(config.getX11Display() != null ? config.getX11Display() : "localhost:0");

        updateKeyFields();
        updateX11Fields();

        loadingProfile = false;
    }

    private void clearFields() {
        loadingProfile = true;

        hostField.setText("");
        portSpinner.setValue(22);
        usernameField.setText(System.getProperty("user.name"));
        passwordField.setText("");
        useKeyAuth.setSelected(false);
        keyFileField.setText(getDefaultKeyFile());
        passphraseField.setText("");
        termTypeCombo.setSelectedItem("xterm-256color");
        colsSpinner.setValue(80);
        rowsSpinner.setValue(24);
        x11ForwardingCheckbox.setSelected(false);
        x11DisplayField.setText(System.getenv("DISPLAY") != null ? System.getenv("DISPLAY") : "localhost:0");

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
        config.setColumns((Integer) colsSpinner.getValue());
        config.setRows((Integer) rowsSpinner.getValue());
        config.setX11Forwarding(x11ForwardingCheckbox.isSelected());
        config.setX11Display(x11DisplayField.getText().trim());
        return config;
    }

    private void saveProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null || selected.equals("<New Connection>")) {
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
            JOptionPane.showMessageDialog(this, "Failed to save profile: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null || selected.equals("<New Connection>")) {
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
                "Delete profile '" + selected + "'?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            try {
                connectionManager.delete(selected);
                refreshProfiles();
                profileCombo.setSelectedItem("<New Connection>");
                clearFields();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to delete profile: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String getDefaultKeyFile() {
        String homeDir = System.getProperty("user.home");
        File defaultKey = new File(homeDir, ".ssh/id_ed25519");
        if (!defaultKey.exists()) {
            defaultKey = new File(homeDir, ".ssh/id_rsa");
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
        portSpinner = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
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
        termTypeCombo = new JComboBox<>(new String[]{
                "xterm-256color", "xterm", "vt100", "vt220", "linux", "ansi"
        });
        panel.add(termTypeCombo, gbc);

        // Size
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Columns:"), gbc);

        gbc.gridx = 1;
        colsSpinner = new JSpinner(new SpinnerNumberModel(80, 40, 320, 1));
        panel.add(colsSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(new JLabel("Rows:"), gbc);

        gbc.gridx = 1;
        rowsSpinner = new JSpinner(new SpinnerNumberModel(24, 10, 100, 1));
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
        x11DisplayField = new JTextField("localhost:0");
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

        // Get password/key info before connecting
        final String password;
        final String keyFile;
        final String passphrase;
        final boolean useKey = useKeyAuth.isSelected();

        if (useKey) {
            keyFile = keyFileField.getText().trim();
            passphrase = new String(passphraseField.getPassword());
            password = null;
            if (keyFile.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please select a key file", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            password = new String(passwordField.getPassword());
            keyFile = null;
            passphrase = null;
            if (password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a password", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        // Run connection in background thread
        Thread connectThread = new Thread(() -> {
            SSHConnection conn = null;
            try {
                conn = new SSHConnection();

                // Host key verification - this will block for user input via invokeAndWait
                conn.setHostKeyVerifier((h, p, keyType, fingerprint, key) -> {
                    int result = JOptionPane.showConfirmDialog(this,
                            "Host key for " + h + ":\n\n" +
                                    "Type: " + keyType + "\n" +
                                    "Fingerprint: " + fingerprint + "\n\n" +
                                    "Accept this key?",
                            "Host Key Verification",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    return result == JOptionPane.YES_OPTION;
                });

                // Connect (includes host key verification)
                conn.connect(host, port, 30000);

                // Now authenticate (separate from connect)
                boolean authenticated;
                if (useKey) {
                    authenticated = conn.authenticatePublicKey(username, keyFile,
                            passphrase.isEmpty() ? null : passphrase, 30000);
                } else {
                    authenticated = conn.authenticatePassword(username, password, 30000);
                }

                if (!authenticated) {
                    throw new IOException("Authentication failed - check username/password");
                }

                // Create terminal
                int cols = (Integer) colsSpinner.getValue();
                int rows = (Integer) rowsSpinner.getValue();
                String termType = (String) termTypeCombo.getSelectedItem();

                // X11 forwarding settings
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
                            x11DisplayNum = 0;
                        }
                    }
                }

                TerminalPanel terminal = new TerminalPanel(cols, rows);

                // Open shell with X11 forwarding if enabled
                final String fx11Host = x11Host;
                final int fx11DisplayNum = x11DisplayNum;
                var shell = conn.openShell(termType, cols, rows, enableX11, fx11Host, fx11DisplayNum);

                // Connect streams
                terminal.setOutputStream(shell.getInvertedIn());

                // Read from shell in background
                final SSHConnection finalConn = conn;
                final TerminalPanel finalTerminal = terminal;
                Thread reader = new Thread(() -> {
                    byte[] buf = new byte[8192];
                    try {
                        InputStream in = shell.getInvertedOut();
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            final byte[] data = buf.clone();
                            final int len = n;
                            SwingUtilities.invokeLater(() -> finalTerminal.write(data, 0, len));
                        }
                        // Stream ended normally - connection closed
                        SwingUtilities.invokeLater(() -> {
                            finalTerminal.displayMessage("*** Connection closed by remote host ***", 9); // Bright red
                        });
                    } catch (IOException e) {
                        // Connection error
                        final String errorMsg = e.getMessage();
                        SwingUtilities.invokeLater(() -> {
                            finalTerminal.displayMessage("*** Connection lost: " + (errorMsg != null ? errorMsg : "Unknown error") + " ***", 9);
                        });
                    }
                });
                reader.setDaemon(true);
                reader.start();

                // Success - update UI on EDT
                final SSHConnection successConn = conn;
                SwingUtilities.invokeLater(() -> {
                    sessionTab = new MainFrame.SessionTab(successConn, terminal);
                    sessionTab.setTitle(username + "@" + host);
                    connected = true;
                    setCursor(Cursor.getDefaultCursor());
                    dispose();
                });

            } catch (Exception e) {
                final SSHConnection failedConn = conn;
                final String errorMsg = e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    setCursor(Cursor.getDefaultCursor());
                    if (failedConn != null) {
                        failedConn.close();
                    }
                    JOptionPane.showMessageDialog(ConnectDialog.this,
                            "Connection failed: " + errorMsg,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        });
        connectThread.start();
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
