package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.sftp.SFTPPanel;
import io.xlogistx.jssh.ssh.SSHConnection;
import io.xlogistx.jssh.terminal.TerminalPanel;

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
    private List<SessionTab> sessions = new ArrayList<>();
    private JLabel statusLabel;
    
    public MainFrame() {
        super("JSSH - Java SSH Client");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(900, 600);
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
        int port = 22;
        
        if (input.contains("@")) {
            String[] parts = input.split("@");
            user = parts[0];
            input = parts[1];
        }
        
        if (input.contains(":")) {
            String[] parts = input.split(":");
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        } else {
            host = input;
        }
        
        quickConnect(host, port, user);
    }
    
    public void quickConnect(String host, int port, String user) {
        if (user == null) {
            user = System.getProperty("user.name");
        }
        
        String password = showPasswordDialog("Password for " + user + "@" + host);
        if (password == null) return;
        
        connectWithPassword(host, port, user, password);
    }
    
    private String showPasswordDialog(String prompt) {
        JPasswordField passwordField = new JPasswordField();
        int result = JOptionPane.showConfirmDialog(this, 
            new Object[] { prompt, passwordField },
            "Authentication", 
            JOptionPane.OK_CANCEL_OPTION);
        
        if (result == JOptionPane.OK_OPTION) {
            return new String(passwordField.getPassword());
        }
        return null;
    }
    
    public void connectWithPassword(String host, int port, String username, String password) {
        // Create connection in background thread
        SwingWorker<SessionTab, Void> worker = new SwingWorker<>() {
            private String error = null;
            
            @Override
            protected SessionTab doInBackground() {
                SSHConnection conn = new SSHConnection();
                
                // Host key verification
                conn.setHostKeyVerifier((h, p, keyType, fingerprint, key) -> {
                    int result = JOptionPane.showConfirmDialog(MainFrame.this,
                        "Host key for " + h + ":\n\n" +
                        "Type: " + keyType + "\n" +
                        "Fingerprint: " + fingerprint + "\n\n" +
                        "Accept this key?",
                        "Host Key Verification",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                    return result == JOptionPane.YES_OPTION;
                });
                
                try {
                    statusLabel.setText(" Connecting to " + host + "...");
                    conn.connect(host, port, 30000);
                    
                    statusLabel.setText(" Authenticating...");
                    if (!conn.authenticatePassword(username, password, 30000)) {
                        error = "Authentication failed";
                        conn.close();
                        return null;
                    }
                    
                    // Create terminal
                    TerminalPanel terminal = new TerminalPanel(80, 24);
                    
                    // Open shell
                    var shell = conn.openShell("xterm-256color", 80, 24);
                    
                    // Connect streams
                    terminal.setOutputStream(shell.getInvertedIn());
                    
                    // Read from shell in background
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
                                finalTerminal.displayMessage("*** Connection closed by remote host ***", 9);
                                statusLabel.setText(" Disconnected");
                            });
                        } catch (IOException e) {
                            // Connection error
                            final String errorMsg = e.getMessage();
                            SwingUtilities.invokeLater(() -> {
                                finalTerminal.displayMessage("*** Connection lost: " + (errorMsg != null ? errorMsg : "Unknown error") + " ***", 9);
                                statusLabel.setText(" Disconnected");
                            });
                        }
                    });
                    reader.setDaemon(true);
                    reader.start();
                    
                    SessionTab tab = new SessionTab(conn, terminal);
                    tab.setTitle(username + "@" + host);
                    
                    return tab;
                    
                } catch (Exception e) {
                    error = e.getMessage();
                    conn.close();
                    return null;
                }
            }
            
            @Override
            protected void done() {
                try {
                    SessionTab tab = get();
                    if (tab != null) {
                        addSession(tab);
                        statusLabel.setText(" Connected to " + host);
                    } else {
                        statusLabel.setText(" Connection failed");
                        JOptionPane.showMessageDialog(MainFrame.this,
                            "Connection failed: " + error,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    statusLabel.setText(" Connection failed");
                    JOptionPane.showMessageDialog(MainFrame.this,
                        "Connection failed: " + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        
        worker.execute();
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
                } catch (IOException e) { }
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
            sftpFrame.setSize(800, 600);
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
            "JSSH - Java SSH Client\n\n" +
            "Version 1.0.0\n\n" +
            "A modern SSH client using Apache MINA SSHD\n\n" +
            "Features:\n" +
            "• Ed25519, ECDSA, RSA key support\n" +
            "• VT100/ANSI terminal emulation\n" +
            "• SFTP file browser\n" +
            "• Port forwarding tunnels\n" +
            "• Password and public key authentication",
            "About JSSH",
            JOptionPane.INFORMATION_MESSAGE);
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
        private SSHConnection connection;
        private TerminalPanel terminal;
        private JPanel panel;
        private String title;
        
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
        
        public SSHConnection getConnection() { return connection; }
        public TerminalPanel getTerminal() { return terminal; }
        public JPanel getPanel() { return panel; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        
        public void close() {
            connection.close();
        }
    }

    /**
     * Detached window for a terminal session
     */
    public class DetachedSessionFrame extends JFrame {
        private SessionTab session;
        private JLabel statusLabel;
        private JSplitPane splitPane;
        private SFTPPanel sftpPanel;
        private boolean sftpVisible = false;
        private JMenuItem toggleSftpItem;

        public DetachedSessionFrame(SessionTab session) {
            super(session.getTitle());
            this.session = session;

            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(900, 600);
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
                    } catch (IOException ex) { }
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
