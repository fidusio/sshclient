package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.config.JSSHConst;
import io.xlogistx.jssh.config.KnownHostsManager;
import io.xlogistx.jssh.sftp.SFTPPanel;
import io.xlogistx.jssh.ssh.SSHConnection;
import io.xlogistx.jssh.terminal.TerminalPanel;
import org.apache.sshd.client.channel.ChannelShell;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Main application frame for JSSH
 */
public class MainFrame extends JFrame {

    private JTabbedPane tabbedPane;
    private final List<SessionTab> sessions = new ArrayList<>();
    private JLabel statusLabel;

    public MainFrame() {
        super(JSSHConst.APP_NAME);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(JSSHConst.MAIN_WINDOW_WIDTH, JSSHConst.MAIN_WINDOW_HEIGHT);
        setLocationRelativeTo(null);

        initUI();
        initMenuBar();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // Tabbed pane for sessions
        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabbedPane, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createLoweredBevelBorder());
        statusLabel = new JLabel(" Ready");
        statusBar.add(statusLabel, BorderLayout.WEST);
        add(statusBar, BorderLayout.SOUTH);

        // Toolbar
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> showConnectDialog());
        toolbar.add(connectBtn);

        JButton disconnectBtn = new JButton("Disconnect");
        disconnectBtn.addActionListener(e -> disconnectCurrentSession());
        toolbar.add(disconnectBtn);

        JButton detachBtn = new JButton("Detach");
        detachBtn.setToolTipText("Detach current tab to separate window (Ctrl+Shift+D)");
        detachBtn.addActionListener(e -> detachCurrentSession());
        toolbar.add(detachBtn);

        JButton cloneBtn = new JButton("Clone");
        cloneBtn.setToolTipText("Clone current session in a new window (Ctrl+Shift+C)");
        cloneBtn.addActionListener(e -> cloneCurrentSession());
        toolbar.add(cloneBtn);

        toolbar.addSeparator();

        JButton sftpBtn = new JButton("SFTP");
        sftpBtn.addActionListener(e -> openSFTPForCurrentSession());
        toolbar.add(sftpBtn);

        JButton tunnelBtn = new JButton("Tunnels");
        tunnelBtn.addActionListener(e -> showTunnelDialog());
        toolbar.add(tunnelBtn);

        add(toolbar, BorderLayout.NORTH);
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem connectItem = new JMenuItem("Connect...", KeyEvent.VK_C);
        connectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        connectItem.addActionListener(e -> showConnectDialog());
        fileMenu.add(connectItem);

        JMenuItem quickConnectItem = new JMenuItem("Quick Connect...", KeyEvent.VK_Q);
        quickConnectItem.addActionListener(e -> showQuickConnectDialog());
        fileMenu.add(quickConnectItem);

        fileMenu.addSeparator();

        JMenuItem disconnectItem = new JMenuItem("Disconnect", KeyEvent.VK_D);
        disconnectItem.addActionListener(e -> disconnectCurrentSession());
        fileMenu.add(disconnectItem);

        JMenuItem closeTabItem = new JMenuItem("Close Tab", KeyEvent.VK_W);
        closeTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        closeTabItem.addActionListener(e -> closeCurrentTab());
        fileMenu.add(closeTabItem);

        JMenuItem detachTabItem = new JMenuItem("Detach Tab", KeyEvent.VK_T);
        detachTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        detachTabItem.addActionListener(e -> detachCurrentSession());
        fileMenu.add(detachTabItem);

        JMenuItem cloneTabItem = new JMenuItem("Clone Session", KeyEvent.VK_L);
        cloneTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        cloneTabItem.addActionListener(e -> cloneCurrentSession());
        fileMenu.add(cloneTabItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit", KeyEvent.VK_X);
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitItem.addActionListener(e -> exitApplication());
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        JMenuItem copyItem = new JMenuItem("Copy", KeyEvent.VK_C);
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        editMenu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem("Paste", KeyEvent.VK_P);
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        editMenu.add(pasteItem);

        editMenu.addSeparator();

        JMenuItem clearItem = new JMenuItem("Clear Screen");
        clearItem.addActionListener(e -> clearCurrentTerminal());
        editMenu.add(clearItem);

        menuBar.add(editMenu);

        // Tools menu
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.setMnemonic(KeyEvent.VK_T);

        JMenuItem sftpItem = new JMenuItem("SFTP Browser", KeyEvent.VK_S);
        sftpItem.addActionListener(e -> openSFTPForCurrentSession());
        toolsMenu.add(sftpItem);

        JMenuItem tunnelItem = new JMenuItem("Port Tunnels...", KeyEvent.VK_T);
        tunnelItem.addActionListener(e -> showTunnelDialog());
        toolsMenu.add(tunnelItem);

        toolsMenu.addSeparator();

        JMenuItem keysItem = new JMenuItem("Key Manager...", KeyEvent.VK_K);
        keysItem.addActionListener(e -> showKeyManager());
        toolsMenu.add(keysItem);

        menuBar.add(toolsMenu);

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem howToItem = new JMenuItem("How to Use JSSH", KeyEvent.VK_H);
        howToItem.addActionListener(e -> showHowTo());
        helpMenu.add(howToItem);

        helpMenu.addSeparator();

        JMenuItem aboutItem = new JMenuItem("About", KeyEvent.VK_A);
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    public void showConnectDialog() {
        ConnectDialog dialog = new ConnectDialog(this);
        dialog.setVisible(true);

        if (dialog.isConnected()) {
            SessionTab tab = dialog.getSessionTab();
            addSession(tab);
        }
    }

    private void showQuickConnectDialog() {
        String input = JOptionPane.showInputDialog(this,
                "Enter connection (user@host:port):",
                "Quick Connect",
                JOptionPane.PLAIN_MESSAGE);

        if (input != null && !input.trim().isEmpty()) {
            parseAndConnect(input.trim());
        }
    }

    private void parseAndConnect(String input) {
        String user = System.getProperty("user.name");
        String host;
        int port = JSSHConst.DEFAULT_SSH_PORT;

        // Use the last '@' as separator so usernames containing '@' still work
        int at = input.lastIndexOf('@');
        if (at >= 0) {
            if (at > 0) {
                user = input.substring(0, at);
            }
            input = input.substring(at + 1);
        }

        try {
            if (input.startsWith("[")) {
                // Bracketed IPv6: [addr] or [addr]:port
                int end = input.indexOf(']');
                if (end < 0) {
                    throw new IllegalArgumentException("missing ']'");
                }
                host = input.substring(1, end);
                if (end + 1 < input.length()) {
                    if (input.charAt(end + 1) != ':') {
                        throw new IllegalArgumentException("expected ':' after ']'");
                    }
                    port = Integer.parseInt(input.substring(end + 2));
                }
            } else {
                int colon = input.indexOf(':');
                // A single colon separates host:port; multiple colons mean a bare IPv6 address
                if (colon >= 0 && colon == input.lastIndexOf(':')) {
                    host = input.substring(0, colon);
                    port = Integer.parseInt(input.substring(colon + 1));
                } else {
                    host = input;
                }
            }

            if (host.isEmpty()) {
                throw new IllegalArgumentException("empty host");
            }
            if (port < JSSHConst.MIN_PORT || port > JSSHConst.MAX_PORT) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this,
                    "Invalid connection string.\n" +
                            "Expected: user@host, user@host:port or user@[ipv6]:port\n" +
                            "(" + e.getMessage() + ")",
                    "Quick Connect",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        quickConnect(host, port, user);
    }

    public void quickConnect(String host, int port, String user) {
        if (user == null) {
            user = System.getProperty("user.name");
        }

        char[] password = showPasswordDialog("Password for " + user + "@" + host);
        if (password == null) return;

        connectWithPassword(host, port, user, password);
    }

    private char[] showPasswordDialog(String prompt) {
        JPasswordField passwordField = new JPasswordField();
        int result = JOptionPane.showConfirmDialog(this,
                new Object[]{prompt, passwordField},
                "Authentication",
                JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            return passwordField.getPassword();
        }
        return null;
    }

    public void connectWithPassword(String host, int port, String username, char[] password) {
        // Quick connect: password auth with the default terminal settings
        SessionSpec spec = new SessionSpec(host, port, username, password, null, null,
                JSSHConst.DEFAULT_TERMINAL_TYPE, JSSHConst.DEFAULT_TERMINAL_COLS, JSSHConst.DEFAULT_TERMINAL_ROWS,
                false, null, 0);
        // The spec holds its own copy - wipe the caller's array now
        java.util.Arrays.fill(password, '\0');

        statusLabel.setText(" Connecting to " + host + "...");
        openSession(this, spec, username + "@" + host,
                () -> statusLabel.setText(" Disconnected"),
                tab -> {
                    addSession(tab);
                    statusLabel.setText(" Connected to " + host);
                },
                error -> {
                    statusLabel.setText(" Connection failed");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Connection failed: " + error,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
    }

    /**
     * Everything needed to open a session: endpoint, credentials and the
     * terminal/X11 settings the shell is requested with. Holds its own copies of
     * the secret arrays; {@link #wipe()} zeroes them once a {@link SessionTab}
     * has taken its own copies. Build it on the EDT (it reads nothing from
     * Swing itself, but its inputs usually come from Swing fields).
     */
    public static final class SessionSpec {
        final String host;
        final int port;
        final String username;
        final char[] password;   // null if using key auth
        final String keyFile;    // null if using password auth
        final char[] passphrase;
        final String termType;
        final int cols;
        final int rows;
        final boolean x11Forwarding;
        final String x11Host;
        final int x11DisplayNum;
        final String pasteLineEnding;   // AUTO / LF / CR / CRLF

        public SessionSpec(String host, int port, String username, char[] password, String keyFile, char[] passphrase,
                           String termType, int cols, int rows,
                           boolean x11Forwarding, String x11Host, int x11DisplayNum) {
            this(host, port, username, password, keyFile, passphrase, termType, cols, rows,
                    x11Forwarding, x11Host, x11DisplayNum, JSSHConst.PASTE_LINE_ENDING_AUTO);
        }

        public SessionSpec(String host, int port, String username, char[] password, String keyFile, char[] passphrase,
                           String termType, int cols, int rows,
                           boolean x11Forwarding, String x11Host, int x11DisplayNum, String pasteLineEnding) {
            this.pasteLineEnding = pasteLineEnding != null ? pasteLineEnding : JSSHConst.PASTE_LINE_ENDING_AUTO;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password != null ? password.clone() : null;
            this.keyFile = keyFile;
            this.passphrase = passphrase != null ? passphrase.clone() : null;
            this.termType = termType != null ? termType : JSSHConst.DEFAULT_TERMINAL_TYPE;
            this.cols = cols > 0 ? cols : JSSHConst.DEFAULT_TERMINAL_COLS;
            this.rows = rows > 0 ? rows : JSSHConst.DEFAULT_TERMINAL_ROWS;
            this.x11Forwarding = x11Forwarding;
            this.x11Host = x11Host;
            this.x11DisplayNum = x11DisplayNum;
        }

        /**
         * Snapshot an existing tab for cloning. Must run on the EDT: it copies the
         * credentials before any worker starts (so a source tab closed and wiped
         * mid-clone cannot blank them) and reads the terminal's current size so
         * the clone opens at the size the user is actually looking at.
         */
        static SessionSpec forClone(SessionTab tab) {
            int cols = tab.getTerminal().getCols();
            int rows = tab.getTerminal().getRows();
            if (cols <= 0 || rows <= 0) {
                cols = tab.getCols();
                rows = tab.getRows();
            }
            return new SessionSpec(tab.getHost(), tab.getPort(), tab.getUsername(),
                    tab.getPassword(), tab.getKeyFile(), tab.getPassphrase(),
                    tab.getTermType(), cols, rows,
                    tab.isX11Forwarding(), tab.getX11Host(), tab.getX11DisplayNum(),
                    tab.getPasteLineEnding());
        }

        boolean isKeyAuth() {
            return keyFile != null && !keyFile.isEmpty();
        }

        public void wipe() {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
            if (passphrase != null) {
                java.util.Arrays.fill(passphrase, '\0');
            }
        }
    }

    /**
     * Host key verifier backed by {@code ~/.jssh/known_hosts}: silently accepts a
     * known key, warns loudly on a changed key, and asks (with "remember") for an
     * unknown one. {@link SSHConnection} marshals the call onto the EDT, so the
     * dialogs are safe to show here.
     */
    static SSHConnection.HostKeyVerifier createHostKeyVerifier(Component parent) {
        return (h, p, keyType, fingerprint, key) -> {
            KnownHostsManager knownHosts = KnownHostsManager.getInstance();
            KnownHostsManager.VerifyResult verifyResult = knownHosts.verify(h, p, fingerprint, key);

            switch (verifyResult) {
                case KNOWN_OK:
                    // Host key is known and matches - auto accept
                    return true;

                case KNOWN_CHANGED:
                    // Host key has changed - security warning!
                    String oldFingerprint = knownHosts.getStoredFingerprint(h, p);
                    int changeResult = JOptionPane.showConfirmDialog(parent,
                            "WARNING: HOST KEY HAS CHANGED!\n\n" +
                                    "Host: " + h + (p != JSSHConst.DEFAULT_SSH_PORT ? ":" + p : "") + "\n" +
                                    "Key type: " + keyType + "\n\n" +
                                    "Old fingerprint:\n" + oldFingerprint + "\n\n" +
                                    "New fingerprint:\n" + fingerprint + "\n\n" +
                                    "This could indicate a man-in-the-middle attack,\n" +
                                    "or the server's host key has been changed.\n\n" +
                                    "Accept the new key?",
                            "Host Key Changed",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.ERROR_MESSAGE);

                    if (changeResult == JOptionPane.YES_OPTION) {
                        // Update the stored key
                        knownHosts.addHost(h, p, keyType, fingerprint, key);
                        return true;
                    }
                    return false;

                case UNKNOWN:
                default:
                    // New host - show dialog with remember checkbox
                    JCheckBox rememberCheckbox = new JCheckBox("Remember this host", true);
                    Object[] message = {
                            "Host key for " + h + (p != JSSHConst.DEFAULT_SSH_PORT ? ":" + p : "") + ":\n\n" +
                                    "Type: " + keyType + "\n" +
                                    "Fingerprint: " + fingerprint + "\n\n" +
                                    "Accept this key?",
                            rememberCheckbox
                    };

                    int result = JOptionPane.showConfirmDialog(parent,
                            message,
                            "Host Key Verification",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);

                    if (result == JOptionPane.YES_OPTION) {
                        if (rememberCheckbox.isSelected()) {
                            knownHosts.addHost(h, p, keyType, fingerprint, key);
                        }
                        return true;
                    }
                    return false;
            }
        };
    }

    /**
     * Connect, authenticate and open a shell for {@code spec} on a worker
     * thread, then build the {@link SessionTab} on the EDT. Exactly one of
     * {@code onSuccess} / {@code onFailure} runs, on the EDT; the spec's secrets
     * are wiped afterwards either way.
     *
     * @param onRemoteClose run on the EDT when the shell stream ends (may be null)
     */
    static void openSession(Component parent, SessionSpec spec, String title, Runnable onRemoteClose,
                            java.util.function.Consumer<SessionTab> onSuccess,
                            java.util.function.Consumer<String> onFailure) {
        SwingWorker<ChannelShell, Void> worker = new SwingWorker<ChannelShell, Void>() {
            private SSHConnection conn;

            @Override
            protected ChannelShell doInBackground() throws Exception {
                conn = new SSHConnection();
                conn.setHostKeyVerifier(createHostKeyVerifier(parent));
                conn.connect(spec.host, spec.port, JSSHConst.CONNECTION_TIMEOUT_MS);

                boolean authSuccess;
                if (spec.isKeyAuth()) {
                    authSuccess = conn.authenticatePublicKey(spec.username, spec.keyFile,
                            spec.passphrase, JSSHConst.AUTH_TIMEOUT_MS);
                } else {
                    authSuccess = conn.authenticatePassword(spec.username, spec.password,
                            JSSHConst.AUTH_TIMEOUT_MS);
                }
                if (!authSuccess) {
                    throw new IOException("Authentication failed");
                }

                return conn.openShell(spec.termType, spec.cols, spec.rows,
                        spec.x11Forwarding, spec.x11Host, spec.x11DisplayNum);
            }

            @Override
            protected void done() {
                try {
                    ChannelShell shell = get();
                    SessionTab tab = attachShell(conn, shell, spec, title, onRemoteClose);
                    onSuccess.accept(tab);
                } catch (Exception e) {
                    if (conn != null) {
                        conn.close();
                    }
                    Throwable cause = (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null)
                            ? e.getCause() : e;
                    onFailure.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                } finally {
                    // The session tab keeps its own copies - wipe ours
                    spec.wipe();
                }
            }
        };
        worker.execute();
    }

    /**
     * Wire an already-open shell to a fresh terminal and wrap both in a
     * {@link SessionTab}. Must be called on the EDT: it constructs a Swing
     * component. The tab remembers the spec's terminal/X11 settings so a later
     * clone reopens the shell the same way.
     */
    static SessionTab attachShell(SSHConnection conn, ChannelShell shell, SessionSpec spec, String title,
                                  Runnable onRemoteClose) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("attachShell must run on the EDT");
        }
        TerminalPanel terminal = new TerminalPanel(spec.cols, spec.rows);
        terminal.setOutputStream(shell.getInvertedIn());
        terminal.setPasteLineEnding(conn.resolvePasteLineEnding(spec.pasteLineEnding));
        startShellReader(shell, terminal, onRemoteClose);

        // X11 was requested but no local MIT-MAGIC-COOKIE was found: GUI apps
        // will be rejected by the X server, so tell the user up front.
        String x11Warning = conn.getX11Warning();
        if (spec.x11Forwarding && x11Warning != null) {
            terminal.displayMessage("*** X11: " + x11Warning + " ***", 11); // Bright yellow
        }

        SessionTab tab = new SessionTab(conn, terminal);
        tab.setTitle(title);
        tab.setConnectionInfo(spec.host, spec.port, spec.username, spec.password, spec.keyFile, spec.passphrase);
        tab.setTerminalSettings(spec.termType, spec.cols, spec.rows,
                spec.x11Forwarding, spec.x11Host, spec.x11DisplayNum, spec.pasteLineEnding);
        return tab;
    }

    /**
     * Pump the shell's output into the terminal from a daemon thread; all
     * terminal writes are marshaled onto the EDT.
     */
    static void startShellReader(ChannelShell shell, TerminalPanel terminal, Runnable onRemoteClose) {
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[JSSHConst.SHELL_READ_BUFFER_SIZE];
            try {
                InputStream in = shell.getInvertedOut();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    final byte[] data = buf.clone();
                    final int len = n;
                    SwingUtilities.invokeLater(() -> terminal.write(data, 0, len));
                }
                // Stream ended normally - connection closed
                SwingUtilities.invokeLater(() -> {
                    terminal.displayMessage("*** Connection closed by remote host ***", 9); // Bright red
                    if (onRemoteClose != null) {
                        onRemoteClose.run();
                    }
                });
            } catch (IOException e) {
                // Connection error
                final String errorMsg = e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    terminal.displayMessage("*** Connection lost: " + (errorMsg != null ? errorMsg : "Unknown error") + " ***", 9);
                    if (onRemoteClose != null) {
                        onRemoteClose.run();
                    }
                });
            }
        }, "jssh-shell-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void addSession(SessionTab tab) {
        sessions.add(tab);

        // Create tab with detach and close buttons
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabPanel.setOpaque(false);

        JLabel titleLabel = new JLabel(tab.getTitle() + " ");
        tabPanel.add(titleLabel);

        JButton detachBtn = new JButton("\u2197"); // ↗ arrow symbol for detach
        detachBtn.setToolTipText("Detach to separate window");
        detachBtn.setMargin(new Insets(0, 2, 0, 2));
        detachBtn.setFocusable(false);
        detachBtn.addActionListener(e -> detachSession(tab));
        tabPanel.add(detachBtn);

        JButton closeBtn = new JButton("\u00d7"); // × symbol
        closeBtn.setToolTipText("Close tab");
        closeBtn.setMargin(new Insets(0, 2, 0, 2));
        closeBtn.setFocusable(false);
        closeBtn.addActionListener(e -> closeTab(tab));
        tabPanel.add(closeBtn);

        tabbedPane.addTab(null, tab.getPanel());
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, tabPanel);
        tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);

        // Set up terminal listener
        tab.getTerminal().setTerminalListener(new TerminalPanel.TerminalListener() {
            @Override
            public void onTitleChange(String title) {
                titleLabel.setText(title + " ");
            }

            @Override
            public void onBell() {
                Toolkit.getDefaultToolkit().beep();
            }

            @Override
            public void onResize(int cols, int rows) {
                try {
                    tab.getConnection().resizeTerminal(cols, rows);
                } catch (IOException e) {
                }
            }
        });

        // Focus terminal after a short delay to ensure it's visible
        SwingUtilities.invokeLater(() -> {
            tab.getPanel().revalidate();
            tab.getPanel().repaint();
            tab.getTerminal().requestFocusInWindow();
        });
    }

    private void closeTab(SessionTab tab) {
        int index = sessions.indexOf(tab);
        if (index >= 0) {
            tab.close();
            sessions.remove(index);
            tabbedPane.removeTabAt(index);
        }
    }

    private void detachSession(SessionTab tab) {
        int index = sessions.indexOf(tab);
        if (index >= 0) {
            // Remove from tabbed pane and session list
            sessions.remove(index);
            tabbedPane.removeTabAt(index);

            // Create detached window
            DetachedSessionFrame detachedFrame = new DetachedSessionFrame(tab);
            detachedFrame.setVisible(true);

            statusLabel.setText(" Session detached: " + tab.getTitle());
        }
    }

    private void detachCurrentSession() {
        SessionTab tab = getCurrentSession();
        if (tab != null) {
            detachSession(tab);
        }
    }

    /**
     * Clone the current session - creates a new connection with same credentials in a separate frame
     */
    private void cloneCurrentSession() {
        SessionTab tab = getCurrentSession();
        if (tab == null) {
            JOptionPane.showMessageDialog(this,
                    "No active session to clone",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        cloneSession(tab);
    }

    /**
     * Clone a session - creates a new SSH connection with same credentials
     */
    public void cloneSession(SessionTab sourceTab) {
        if (sourceTab.getHost() == null) {
            JOptionPane.showMessageDialog(this,
                    "Cannot clone this session - connection info not available",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Snapshot credentials + terminal/X11 settings on the EDT, before the
        // worker starts (the source tab may be closed and wiped meanwhile)
        SessionSpec spec = SessionSpec.forClone(sourceTab);
        String host = spec.host;

        statusLabel.setText(" Cloning session to " + host + "...");

        openSession(this, spec, spec.username + "@" + host + " (clone)", null,
                tab -> {
                    // Open in detached frame
                    DetachedSessionFrame detachedFrame = new DetachedSessionFrame(tab);
                    detachedFrame.setVisible(true);
                    statusLabel.setText(" Cloned session: " + tab.getTitle());
                },
                error -> {
                    statusLabel.setText(" Clone failed");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Clone failed: " + error,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
    }

    private void closeCurrentTab() {
        int index = tabbedPane.getSelectedIndex();
        if (index >= 0 && index < sessions.size()) {
            closeTab(sessions.get(index));
        }
    }

    private void disconnectCurrentSession() {
        SessionTab tab = getCurrentSession();
        if (tab != null) {
            tab.getConnection().disconnect();
            statusLabel.setText(" Disconnected");
        }
    }

    private void clearCurrentTerminal() {
        SessionTab tab = getCurrentSession();
        if (tab != null) {
            tab.getTerminal().clear();
        }
    }

    /**
     * Set the status bar text, marshaling to the EDT if called from another thread.
     */
    private void setStatus(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }
    }

    private SessionTab getCurrentSession() {
        int index = tabbedPane.getSelectedIndex();
        if (index >= 0 && index < sessions.size()) {
            return sessions.get(index);
        }
        return null;
    }

    private void openSFTPForCurrentSession() {
        SessionTab tab = getCurrentSession();
        if (tab == null || !tab.getConnection().isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "No active connection",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            SFTPPanel sftpPanel = new SFTPPanel(tab.getConnection());

            JFrame sftpFrame = new JFrame("SFTP - " + tab.getTitle());
            sftpFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            sftpFrame.add(sftpPanel);
            sftpFrame.setSize(JSSHConst.SFTP_WINDOW_WIDTH, JSSHConst.SFTP_WINDOW_HEIGHT);
            sftpFrame.setLocationRelativeTo(this);

            // Close SFTP client when window is closed
            sftpFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    sftpPanel.close();
                }
            });

            sftpFrame.setVisible(true);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to open SFTP: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showTunnelDialog() {
        SessionTab tab = getCurrentSession();
        if (tab == null || !tab.getConnection().isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "No active connection",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        TunnelDialog dialog = new TunnelDialog(this, tab.getConnection());
        dialog.setVisible(true);
    }

    private void showKeyManager() {
        KeyManagerDialog dialog = new KeyManagerDialog(this);
        dialog.setVisible(true);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                JSSHConst.APP_NAME + " By XLOGISTX.IO \n\n" +
                        "Version " + JSSHConst.VERSION.version() + "\n\n" +
                        "JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n\n" +
                        "A SSH client based on Apache MINA SSHD\n\n" +
                        "Features:\n" +
                        "• Ed25519, ECDSA, RSA key support\n" +
                        "• VT100/ANSI terminal emulation\n" +
                        "• SFTP file browser\n" +
                        "• Port forwarding tunnels\n" +
                        "• X11 forwarding\n" +
                        "• Password and public key authentication",
                "About JSSH",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Render the bundled how-to.md guide (Markdown → HTML via commonmark) in a
     * JEditorPane dialog.
     */
    private void showHowTo() {
        String html;
        try (InputStream is = getClass().getResourceAsStream("/how-to.md")) {
            if (is == null) {
                JOptionPane.showMessageDialog(this, "Guide not found (how-to.md missing from classpath)",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String markdown = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // GFM extensions: how-to.md uses pipe tables (and may use ~~strike~~ / - [ ] lists),
            // which core CommonMark does not parse.
            java.util.List<org.commonmark.Extension> extensions = java.util.List.of(
                    org.commonmark.ext.gfm.tables.TablesExtension.create(),
                    org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
                    org.commonmark.ext.task.list.items.TaskListItemsExtension.create());
            org.commonmark.node.Node document = org.commonmark.parser.Parser.builder()
                    .extensions(extensions).build().parse(markdown);
            String body = org.commonmark.renderer.html.HtmlRenderer.builder()
                    .extensions(extensions).build().render(document);
            html = "<html><head><style>"
                    + "body { font-family: sans-serif; margin: 12px; }"
                    + "h1 { font-size: 1.6em; } h2 { font-size: 1.3em; } h3 { font-size: 1.1em; }"
                    + "code, pre { font-family: monospace; background: #f0f0f0; }"
                    + "pre { padding: 6px; }"
                    + "table, th, td { border: 1px solid #999; border-collapse: collapse; padding: 3px; }"
                    + "</style></head><body>" + body + "</body></html>";
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to load guide: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JEditorPane editor = new JEditorPane("text/html", html);
        editor.setEditable(false);
        editor.setCaretPosition(0);
        // Open any links in the system browser rather than in the (limited) pane
        editor.addHyperlinkListener(ev -> {
            if (ev.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && ev.getURL() != null) {
                try {
                    Desktop.getDesktop().browse(ev.getURL().toURI());
                } catch (Exception ignore) {
                }
            }
        });

        JScrollPane scroll = new JScrollPane(editor);
        scroll.setPreferredSize(new Dimension(720, 560));

        JDialog dialog = new JDialog(this, "How to Use JSSH", false);
        dialog.getContentPane().add(scroll);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void exitApplication() {
        // Close all sessions
        for (SessionTab tab : new ArrayList<>(sessions)) {
            tab.close();
        }
        dispose();
        System.exit(0);
    }

    /**
     * Session tab containing connection and terminal
     */
    public static class SessionTab {
        private final SSHConnection connection;
        private final TerminalPanel terminal;
        private final JPanel panel;
        private String title;

        // Authentication info for cloning - secrets kept as char[] so they can
        // be wiped when the tab closes instead of lingering in the heap
        private String host;
        private int port;
        private String username;
        private char[] password;  // null if using key auth
        private String keyFile;   // null if using password auth
        private char[] passphrase;

        // Terminal / X11 settings the shell was opened with, so clone and
        // reconnect reopen it the same way instead of with the defaults
        private String termType = JSSHConst.DEFAULT_TERMINAL_TYPE;
        private int cols = JSSHConst.DEFAULT_TERMINAL_COLS;
        private int rows = JSSHConst.DEFAULT_TERMINAL_ROWS;
        private boolean x11Forwarding = false;
        private String x11Host;
        private int x11DisplayNum;
        private String pasteLineEnding = JSSHConst.PASTE_LINE_ENDING_AUTO;

        public SessionTab(SSHConnection connection, TerminalPanel terminal) {
            this.connection = connection;
            this.terminal = terminal;

            panel = new JPanel(new BorderLayout());
            panel.setBackground(Color.BLACK);

            // Don't use scroll pane - terminal handles its own size
            panel.add(terminal, BorderLayout.CENTER);

            // Handle resize
            panel.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    int newCols = panel.getWidth() / terminal.getCharWidth();
                    int newRows = panel.getHeight() / terminal.getCharHeight();
                    if (newCols > 10 && newRows > 5) {
                        terminal.resize(newCols, newRows);
                    }
                }
            });

            // Request focus when panel is shown
            panel.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                    if (panel.isShowing()) {
                        terminal.requestFocusInWindow();
                    }
                }
            });
        }

        public SSHConnection getConnection() {
            return connection;
        }

        public TerminalPanel getTerminal() {
            return terminal;
        }

        public JPanel getPanel() {
            return panel;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        // Authentication info getters/setters for cloning
        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public char[] getPassword() {
            return password;
        }

        public void setPassword(char[] password) {
            this.password = password != null ? password.clone() : null;
        }

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(String keyFile) {
            this.keyFile = keyFile;
        }

        public char[] getPassphrase() {
            return passphrase;
        }

        public void setPassphrase(char[] passphrase) {
            this.passphrase = passphrase != null ? passphrase.clone() : null;
        }

        public boolean isUsingKeyAuth() {
            return keyFile != null && !keyFile.isEmpty();
        }

        /**
         * Store connection info for cloning. The secret arrays are copied so a
         * source tab being closed (and wiped) cannot blank a clone's credentials.
         */
        public void setConnectionInfo(String host, int port, String username, char[] password, String keyFile, char[] passphrase) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password != null ? password.clone() : null;
            this.keyFile = keyFile;
            this.passphrase = passphrase != null ? passphrase.clone() : null;
        }

        /**
         * Remember the terminal type/size and X11 settings the shell was opened
         * with (see {@link SSHConnection#openShell(String, int, int, boolean, String, int)}).
         */
        public void setTerminalSettings(String termType, int cols, int rows,
                                        boolean x11Forwarding, String x11Host, int x11DisplayNum,
                                        String pasteLineEnding) {
            this.termType = termType != null ? termType : JSSHConst.DEFAULT_TERMINAL_TYPE;
            this.cols = cols > 0 ? cols : JSSHConst.DEFAULT_TERMINAL_COLS;
            this.rows = rows > 0 ? rows : JSSHConst.DEFAULT_TERMINAL_ROWS;
            this.x11Forwarding = x11Forwarding;
            this.x11Host = x11Host;
            this.x11DisplayNum = x11DisplayNum;
            this.pasteLineEnding = pasteLineEnding != null ? pasteLineEnding : JSSHConst.PASTE_LINE_ENDING_AUTO;
        }

        public String getPasteLineEnding() {
            return pasteLineEnding;
        }

        public String getTermType() {
            return termType;
        }

        /** Columns the shell was opened with (the terminal may have been resized since). */
        public int getCols() {
            return cols;
        }

        /** Rows the shell was opened with (the terminal may have been resized since). */
        public int getRows() {
            return rows;
        }

        public boolean isX11Forwarding() {
            return x11Forwarding;
        }

        public String getX11Host() {
            return x11Host;
        }

        public int getX11DisplayNum() {
            return x11DisplayNum;
        }

        public void close() {
            // Wipe stored credentials so they don't linger in the heap
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
                password = null;
            }
            if (passphrase != null) {
                java.util.Arrays.fill(passphrase, '\0');
                passphrase = null;
            }
            // Stop the blink timer so the panel (and its scrollback) can be GC'd
            terminal.dispose();
            connection.close();
        }
    }

    /**
     * Detached window for a terminal session
     */
    public static class DetachedSessionFrame extends JFrame {
        private final SessionTab session;
        private JLabel statusLabel;
        private JSplitPane splitPane;
        private SFTPPanel sftpPanel;
        private boolean sftpVisible = false;
        private JMenuItem toggleSftpItem;

        public DetachedSessionFrame(SessionTab session) {
            super(session.getTitle());
            this.session = session;

            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(JSSHConst.MAIN_WINDOW_WIDTH, JSSHConst.MAIN_WINDOW_HEIGHT);
            setLocationRelativeTo(null);

            initUI();
            initMenuBar();

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    closeWindow();
                }
            });

            // Update terminal listener to change this frame's title
            session.getTerminal().setTerminalListener(new TerminalPanel.TerminalListener() {
                @Override
                public void onTitleChange(String title) {
                    setTitle(title);
                }

                @Override
                public void onBell() {
                    Toolkit.getDefaultToolkit().beep();
                }

                @Override
                public void onResize(int cols, int rows) {
                    try {
                        session.getConnection().resizeTerminal(cols, rows);
                    } catch (IOException ex) {
                    }
                }
            });
        }

        private void initUI() {
            setLayout(new BorderLayout());

            // Create split pane with terminal on top
            splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
            splitPane.setTopComponent(session.getPanel());
            splitPane.setResizeWeight(0.6); // Terminal gets 60% by default
            splitPane.setOneTouchExpandable(true);

            // Initially just show the terminal (no bottom component)
            add(splitPane, BorderLayout.CENTER);

            // Status bar
            JPanel statusBar = new JPanel(new BorderLayout());
            statusBar.setBorder(BorderFactory.createLoweredBevelBorder());
            statusLabel = new JLabel(" " + session.getTitle());
            statusBar.add(statusLabel, BorderLayout.WEST);
            add(statusBar, BorderLayout.SOUTH);

            // Focus terminal
            SwingUtilities.invokeLater(() -> session.getTerminal().requestFocusInWindow());
        }

        private void toggleSFTP() {
            if (!session.getConnection().isConnected()) {
                JOptionPane.showMessageDialog(this,
                        "No active connection",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (sftpVisible) {
                // Hide SFTP panel
                splitPane.setBottomComponent(null);
                splitPane.setDividerSize(0);
                sftpVisible = false;
                toggleSftpItem.setText("Show SFTP Browser");
            } else {
                // Show SFTP panel
                try {
                    if (sftpPanel == null) {
                        sftpPanel = new SFTPPanel(session.getConnection());
                        // The panel's Close button closes its SFTP client - remove it
                        // and reset our state so the next Show creates a fresh panel
                        sftpPanel.setOnClose(this::onSftpPanelClosed);
                    }
                    splitPane.setBottomComponent(sftpPanel);
                    splitPane.setDividerSize(8);
                    splitPane.setDividerLocation(0.5);
                    sftpVisible = true;
                    toggleSftpItem.setText("Hide SFTP Browser");
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this,
                            "Failed to open SFTP: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        private void onSftpPanelClosed() {
            splitPane.setBottomComponent(null);
            splitPane.setDividerSize(0);
            sftpPanel = null;
            sftpVisible = false;
            if (toggleSftpItem != null) {
                toggleSftpItem.setText("Show SFTP Browser");
            }
        }

        private void initMenuBar() {
            JMenuBar menuBar = new JMenuBar();

            JMenu fileMenu = new JMenu("File");
            fileMenu.setMnemonic(KeyEvent.VK_F);

            JMenuItem disconnectItem = new JMenuItem("Disconnect", KeyEvent.VK_D);
            disconnectItem.addActionListener(e -> {
                session.getConnection().disconnect();
                statusLabel.setText(" Disconnected");
            });
            fileMenu.add(disconnectItem);

            JMenuItem cloneItem = new JMenuItem("Clone Session", KeyEvent.VK_L);
            cloneItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            cloneItem.addActionListener(e -> cloneSession());
            fileMenu.add(cloneItem);

            fileMenu.addSeparator();

            JMenuItem closeItem = new JMenuItem("Close Window", KeyEvent.VK_C);
            closeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
            closeItem.addActionListener(e -> closeWindow());
            fileMenu.add(closeItem);

            menuBar.add(fileMenu);

            JMenu viewMenu = new JMenu("View");
            viewMenu.setMnemonic(KeyEvent.VK_V);

            toggleSftpItem = new JMenuItem("Show SFTP Browser", KeyEvent.VK_S);
            toggleSftpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
            toggleSftpItem.addActionListener(e -> toggleSFTP());
            viewMenu.add(toggleSftpItem);

            menuBar.add(viewMenu);

            JMenu toolsMenu = new JMenu("Tools");
            toolsMenu.setMnemonic(KeyEvent.VK_T);

            JMenuItem tunnelItem = new JMenuItem("Port Tunnels...", KeyEvent.VK_P);
            tunnelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK));
            tunnelItem.addActionListener(e -> showTunnelDialog());
            toolsMenu.add(tunnelItem);

            menuBar.add(toolsMenu);

            JMenu editMenu = new JMenu("Edit");
            editMenu.setMnemonic(KeyEvent.VK_E);

            JMenuItem clearItem = new JMenuItem("Clear Screen");
            clearItem.addActionListener(e -> session.getTerminal().clear());
            editMenu.add(clearItem);

            menuBar.add(editMenu);

            setJMenuBar(menuBar);
        }

        private void showTunnelDialog() {
            if (!session.getConnection().isConnected()) {
                JOptionPane.showMessageDialog(this,
                        "No active connection",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            TunnelDialog dialog = new TunnelDialog(this, session.getConnection());
            dialog.setVisible(true);
        }

        /**
         * Clone this session - creates a new SSH connection with same credentials
         */
        private void cloneSession() {
            if (session.getHost() == null) {
                JOptionPane.showMessageDialog(this,
                        "Cannot clone this session - connection info not available",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            final DetachedSessionFrame parentFrame = this;

            // Snapshot credentials + terminal/X11 settings on the EDT before the
            // worker starts (this window may be closed and wiped meanwhile)
            SessionSpec spec = SessionSpec.forClone(session);

            statusLabel.setText(" Cloning session...");

            openSession(parentFrame, spec, spec.username + "@" + spec.host + " (clone)", null,
                    tab -> {
                        DetachedSessionFrame detachedFrame = new DetachedSessionFrame(tab);
                        detachedFrame.setVisible(true);
                        statusLabel.setText(" " + session.getTitle());
                    },
                    error -> {
                        statusLabel.setText(" Clone failed");
                        JOptionPane.showMessageDialog(parentFrame,
                                "Clone failed: " + error,
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    });
        }

        private void closeWindow() {
            int result = JOptionPane.showConfirmDialog(this,
                    "Close this session?",
                    "Confirm Close",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                if (sftpPanel != null) {
                    sftpPanel.close();
                }
                session.close();
                dispose();
            }
        }
    }
}
