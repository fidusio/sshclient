package io.xlogistx.jssh.sftp;

import io.xlogistx.jssh.config.JSSHConst;
import io.xlogistx.jssh.ssh.SSHConnection;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.Attributes;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * SFTP file browser panel - MindTerm style.
 *
 * <p>Threading: every SFTP call runs off the EDT in a {@link SwingWorker}; list
 * models, dialogs and status text are only touched in {@code done()}. The
 * {@code remotePath} field is EDT-owned - workers never read it, they get the
 * directory captured on the EDT when the operation was started.
 */
public class SFTPPanel extends JPanel {

    private SSHConnection connection;
    /** Set on the EDT once the connect worker finishes; null until then and after close(). */
    private volatile SftpClient sftpClient;
    /** close() was called; if the connect worker is still running it closes the client on completion. */
    private volatile boolean closed;

    // Local side
    private String localPath;
    private JComboBox<String> localPathCombo;
    private JList<FileItem> localList;
    private DefaultListModel<FileItem> localListModel;
    private JLabel localInfoLabel;

    // Remote side
    private String remotePath = JSSHConst.SFTP_DEFAULT_REMOTE_PATH;
    private JComboBox<String> remotePathCombo;
    private JList<FileItem> remoteList;
    private DefaultListModel<FileItem> remoteListModel;
    private JLabel remoteInfoLabel;
    /** Buttons that start a mutating/long remote operation; disabled while one is running. */
    private final List<AbstractButton> remoteOpButtons = new ArrayList<>();
    /** EDT-only: a mutating/long remote operation (connect, mkdir, rename, delete, transfer) is running. */
    private boolean remoteBusy;
    /** EDT-only: bumped per listing request so a stale listing can't overwrite a newer one. */
    private int listingGeneration;

    // Status
    private JLabel statusLabel;
    private JProgressBar progressBar;

    // Invoked after the Close button closed the SFTP client, so an embedding
    // container (e.g. DetachedSessionFrame) can remove the panel and update its state
    private Runnable onCloseAction;

    /**
     * Builds the panel and starts opening the SFTP channel in the background.
     * The panel is usable immediately: the local pane is populated, the remote
     * pane shows "connecting" until the channel is open and the home directory
     * has been listed. A connect failure is reported in an error dialog and the
     * remote pane stays disabled.
     */
    public SFTPPanel(SSHConnection connection) throws IOException {
        this.connection = connection;
        this.localPath = System.getProperty("user.home");

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        initUI();

        // Load initial directories
        loadLocalDirectory(localPath);
        connectRemote();
    }

    /** Opens the SFTP channel and resolves the remote home directory off the EDT. */
    private void connectRemote() {
        beginRemoteOp();
        remoteInfoLabel.setText("Remote System : connecting...");
        progressBar.setString("Connecting...");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        final SSHConnection conn = connection;
        SwingWorker<SftpClient, Void> worker = new SwingWorker<SftpClient, Void>() {
            private String home = JSSHConst.SFTP_DEFAULT_REMOTE_PATH;

            @Override
            protected SftpClient doInBackground() throws Exception {
                SftpClient client = conn.openSftp();
                // Try to get remote home directory
                try {
                    home = client.canonicalPath(".");
                } catch (Exception e) {
                    home = JSSHConst.SFTP_DEFAULT_REMOTE_PATH;
                }
                return client;
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                SftpClient client;
                try {
                    client = get();
                } catch (Exception e) {
                    // No client: leave remoteBusy set so the remote operations stay disabled
                    remoteInfoLabel.setText("Remote System : not connected");
                    progressBar.setString("SFTP connection failed");
                    JOptionPane.showMessageDialog(SFTPPanel.this,
                        "Failed to open SFTP: " + causeMessage(e), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (closed) {
                    // Closed while connecting - release the channel we just opened
                    try { client.close(); } catch (IOException ignored) { }
                    return;
                }
                sftpClient = client;
                remotePath = home;
                progressBar.setString("");
                endRemoteOp();
                loadRemoteDirectory(home);
            }
        };
        worker.execute();
    }

    private void initUI() {
        // Main panel with local, buttons, remote
        JPanel mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Local panel
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 0.45; gbc.weighty = 1.0;
        mainPanel.add(createLocalPanel(), gbc);

        // Transfer buttons in middle
        gbc.gridx = 1;
        gbc.weightx = 0.1;
        mainPanel.add(createTransferButtons(), gbc);

        // Remote panel
        gbc.gridx = 2;
        gbc.weightx = 0.45;
        mainPanel.add(createRemotePanel(), gbc);

        add(mainPanel, BorderLayout.CENTER);

        // Bottom panel with status and close
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setPreferredSize(new Dimension(200, 20));
        bottomPanel.add(progressBar, BorderLayout.CENTER);

        // Close button
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> {
            close();
            if (onCloseAction != null) {
                // Embedded (e.g., DetachedSessionFrame) - let the host remove the panel
                onCloseAction.run();
            } else {
                // Standalone window - dispose it
                Window window = SwingUtilities.getWindowAncestor(this);
                if (window != null) {
                    window.dispose();
                }
            }
        });
        bottomPanel.add(closeBtn, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createLocalPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // Info label at top
        localInfoLabel = new JLabel("Local System : 0 files (0 B)");
        localInfoLabel.setFont(localInfoLabel.getFont().deriveFont(Font.BOLD));
        panel.add(localInfoLabel, BorderLayout.NORTH);

        // Path combo with browse button
        localPathCombo = new JComboBox<>();
        localPathCombo.setEditable(true);
        localPathCombo.addActionListener(e -> {
            if (e.getActionCommand().equals("comboBoxEdited")) {
                String path = (String) localPathCombo.getSelectedItem();
                if (path != null && !path.equals(localPath)) {
                    loadLocalDirectory(path);
                }
            }
        });

        JButton localBrowseBtn = new JButton("...");
        localBrowseBtn.setMargin(new Insets(0, 5, 0, 5));
        localBrowseBtn.addActionListener(e -> browseLocalDir());

        JPanel pathPanel = new JPanel(new BorderLayout(2, 0));
        pathPanel.add(localPathCombo, BorderLayout.CENTER);
        pathPanel.add(localBrowseBtn, BorderLayout.EAST);

        // File list
        localListModel = new DefaultListModel<>();
        localList = new JList<>(localListModel);
        localList.setCellRenderer(new FileListCellRenderer());
        localList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        localList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FileItem item = localList.getSelectedValue();
                    if (item != null && item.isDirectory) {
                        String newPath;
                        if (item.isParent()) {
                            newPath = new File(localPath).getParent();
                        } else {
                            newPath = new File(localPath, item.getRealName()).getAbsolutePath();
                        }
                        if (newPath != null) {
                            loadLocalDirectory(newPath);
                        }
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(localList);
        scrollPane.setPreferredSize(new Dimension(250, 300));

        JPanel listPanel = new JPanel(new BorderLayout(2, 2));
        listPanel.add(pathPanel, BorderLayout.NORTH);
        listPanel.add(scrollPane, BorderLayout.CENTER);
        panel.add(listPanel, BorderLayout.CENTER);

        // Buttons at bottom
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));

        JButton chDirBtn = new JButton("ChDir");
        chDirBtn.addActionListener(e -> browseLocalDir());
        btnPanel.add(chDirBtn);

        JButton mkDirBtn = new JButton("MkDir");
        mkDirBtn.addActionListener(e -> createLocalDir());
        btnPanel.add(mkDirBtn);

        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> renameLocal());
        btnPanel.add(renameBtn);

        JPanel btnPanel2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteLocal());
        btnPanel2.add(deleteBtn);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadLocalDirectory(localPath));
        btnPanel2.add(refreshBtn);

        JPanel allBtns = new JPanel(new GridLayout(2, 1));
        allBtns.add(btnPanel);
        allBtns.add(btnPanel2);
        panel.add(allBtns, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createRemotePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // Info label at top
        remoteInfoLabel = new JLabel("Remote System : 0 files (0 B)");
        remoteInfoLabel.setFont(remoteInfoLabel.getFont().deriveFont(Font.BOLD));
        panel.add(remoteInfoLabel, BorderLayout.NORTH);

        // Path combo with go button
        remotePathCombo = new JComboBox<>();
        remotePathCombo.setEditable(true);
        remotePathCombo.addActionListener(e -> {
            if (e.getActionCommand().equals("comboBoxEdited")) {
                String path = (String) remotePathCombo.getSelectedItem();
                if (path != null && !path.equals(remotePath)) {
                    loadRemoteDirectory(path);
                }
            }
        });

        JButton remoteGoBtn = new JButton("Go");
        remoteGoBtn.setMargin(new Insets(0, 5, 0, 5));
        remoteGoBtn.addActionListener(e -> {
            String path = (String) remotePathCombo.getSelectedItem();
            if (path != null && !path.isEmpty()) {
                loadRemoteDirectory(path);
            }
        });

        JPanel pathPanel = new JPanel(new BorderLayout(2, 0));
        pathPanel.add(remotePathCombo, BorderLayout.CENTER);
        pathPanel.add(remoteGoBtn, BorderLayout.EAST);

        // File list
        remoteListModel = new DefaultListModel<>();
        remoteList = new JList<>(remoteListModel);
        remoteList.setCellRenderer(new FileListCellRenderer());
        remoteList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        remoteList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FileItem item = remoteList.getSelectedValue();
                    if (item != null && item.isDirectory) {
                        String newPath;
                        if (item.isParent()) {
                            newPath = getParentPath(remotePath);
                        } else {
                            newPath = joinRemote(remotePath, item.getRealName());
                        }
                        loadRemoteDirectory(newPath);
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(remoteList);
        scrollPane.setPreferredSize(new Dimension(250, 300));

        JPanel listPanel = new JPanel(new BorderLayout(2, 2));
        listPanel.add(pathPanel, BorderLayout.NORTH);
        listPanel.add(scrollPane, BorderLayout.CENTER);
        panel.add(listPanel, BorderLayout.CENTER);

        // Buttons at bottom
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));

        JButton chDirBtn = new JButton("ChDir");
        chDirBtn.addActionListener(e -> browseRemoteDir());
        btnPanel.add(chDirBtn);
        remoteOpButtons.add(chDirBtn);

        JButton mkDirBtn = new JButton("MkDir");
        mkDirBtn.addActionListener(e -> createRemoteDir());
        btnPanel.add(mkDirBtn);
        remoteOpButtons.add(mkDirBtn);

        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> renameRemote());
        btnPanel.add(renameBtn);
        remoteOpButtons.add(renameBtn);

        JPanel btnPanel2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteRemote());
        btnPanel2.add(deleteBtn);
        remoteOpButtons.add(deleteBtn);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadRemoteDirectory(remotePath));
        btnPanel2.add(refreshBtn);

        JPanel allBtns = new JPanel(new GridLayout(2, 1));
        allBtns.add(btnPanel);
        allBtns.add(btnPanel2);
        panel.add(allBtns, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTransferButtons() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 5, 10, 5);

        // Download button (remote to local)
        JButton downloadBtn = new JButton("<--");
        downloadBtn.setToolTipText("Download (Remote to Local)");
        downloadBtn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        downloadBtn.addActionListener(e -> downloadSelected());
        gbc.gridy = 0;
        panel.add(downloadBtn, gbc);
        remoteOpButtons.add(downloadBtn);

        // Upload button (local to remote)
        JButton uploadBtn = new JButton("-->");
        uploadBtn.setToolTipText("Upload (Local to Remote)");
        uploadBtn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        uploadBtn.addActionListener(e -> uploadSelected());
        gbc.gridy = 1;
        panel.add(uploadBtn, gbc);
        remoteOpButtons.add(uploadBtn);

        return panel;
    }

    // ---- remote operation guard (EDT only) ----

    /**
     * Marks a mutating/long remote operation as running and disables the buttons
     * that would start another. Returns false (and does nothing) if one is
     * already running or there is no SFTP client yet.
     */
    private boolean beginRemoteOp() {
        if (remoteBusy) return false;
        remoteBusy = true;
        setRemoteOpButtonsEnabled(false);
        return true;
    }

    private void endRemoteOp() {
        remoteBusy = false;
        setRemoteOpButtonsEnabled(true);
    }

    private void setRemoteOpButtonsEnabled(boolean enabled) {
        for (AbstractButton b : remoteOpButtons) {
            b.setEnabled(enabled);
        }
    }

    /** True when a remote operation may be started: connected and nothing else running. */
    private boolean remoteReady() {
        return sftpClient != null && !remoteBusy;
    }

    /** An SFTP call to run off the EDT. */
    @FunctionalInterface
    private interface RemoteOp {
        void run(SftpClient client) throws IOException;
    }

    /**
     * Runs {@code op} on a worker thread under the busy guard. {@code onDone} is
     * called on the EDT with {@code null} on success or the failure cause.
     * The caller must have checked {@link #remoteReady()}.
     */
    private void runRemoteOp(RemoteOp op, Consumer<Exception> onDone) {
        if (!beginRemoteOp()) return;
        final SftpClient client = sftpClient;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                op.run(client);
                return null;
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                endRemoteOp();
                Exception failure = null;
                try {
                    get();
                } catch (Exception e) {
                    failure = e;
                }
                onDone.accept(failure);
            }
        };
        worker.execute();
    }

    /** Message of the real failure behind a SwingWorker.get() exception. */
    static String causeMessage(Exception e) {
        Throwable t = e;
        if (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null ? msg : t.toString();
    }

    private void loadLocalDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Invalid directory: " + path, "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        localPath = dir.getAbsolutePath();
        localListModel.clear();

        // Update combo
        localPathCombo.removeAllItems();
        localPathCombo.addItem(localPath);

        // Add parent directory entry
        if (dir.getParentFile() != null) {
            localListModel.addElement(FileItem.parent());
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        // Sort: directories first, then by name
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        long totalSize = 0;
        int fileCount = 0;

        for (File file : files) {
            String name = file.getName();
            // File.isDirectory() follows links, so a symlink to a directory is
            // listed as a directory and marked [@name] like the remote pane does.
            boolean isDir = file.isDirectory();
            boolean isSymlink = java.nio.file.Files.isSymbolicLink(file.toPath());
            long size = file.length();

            if (isDir) {
                localListModel.addElement(new FileItem(name, true, isSymlink, 0));
            } else {
                localListModel.addElement(new FileItem(name, false, isSymlink, size));
                totalSize += size;
            }
            fileCount++;
        }

        localInfoLabel.setText("Local System : " + fileCount + " files (" + formatSize(totalSize) + ")");
    }

    /**
     * Lists {@code path} into a sorted item list: directories first, "[..]" on top
     * unless at the root, symlinks resolved so a link to a directory counts as one.
     */
    private static List<FileItem> listRemoteDirectory(SftpClient client, String path, boolean dirsOnly,
                                                      long[] totalSizeOut, int[] fileCountOut) throws IOException {
        List<DirEntry> entries = new ArrayList<>();
        for (DirEntry entry : client.readDir(path)) {
            entries.add(entry);
        }

        // Sort: directories first, then by name
        entries.sort((a, b) -> {
            boolean aDir = a.getAttributes().isDirectory();
            boolean bDir = b.getAttributes().isDirectory();
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getFilename().compareToIgnoreCase(b.getFilename());
        });

        List<FileItem> items = new ArrayList<>();
        long totalSize = 0;
        int fileCount = 0;

        // Add parent entry
        if (!path.equals("/")) {
            items.add(FileItem.parent());
        }

        for (DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;

            Attributes attrs = entry.getAttributes();
            boolean isDir = attrs.isDirectory();
            boolean isSymlink = attrs.isSymbolicLink();
            long size = attrs.getSize();

            // If it's a symlink, check if it points to a directory
            if (isSymlink && !isDir) {
                try {
                    Attributes targetAttrs = client.stat(joinRemote(path, name));
                    if (targetAttrs.isDirectory()) {
                        isDir = true;
                    }
                } catch (Exception e) {
                    // Link target doesn't exist or can't be accessed
                }
            }

            if (isDir) {
                items.add(new FileItem(name, true, isSymlink, 0));
            } else if (!dirsOnly) {
                items.add(new FileItem(name, false, isSymlink, size));
                totalSize += size;
            } else {
                continue;
            }
            fileCount++;
        }

        if (totalSizeOut != null) totalSizeOut[0] = totalSize;
        if (fileCountOut != null) fileCountOut[0] = fileCount;
        return items;
    }

    private void loadRemoteDirectory(String path) {
        final SftpClient client = sftpClient;
        if (client == null) return; // not connected (yet)
        final int generation = ++listingGeneration;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            private List<FileItem> items = new ArrayList<>();
            private long totalSize = 0;
            private int fileCount = 0;
            private String error = null;

            @Override
            protected Void doInBackground() {
                try {
                    long[] size = new long[1];
                    int[] count = new int[1];
                    items = listRemoteDirectory(client, path, false, size, count);
                    totalSize = size[0];
                    fileCount = count[0];
                } catch (Exception e) {
                    error = e.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (generation != listingGeneration) {
                    // A newer listing was requested meanwhile; it owns the UI now
                    return;
                }
                setCursor(Cursor.getDefaultCursor());

                if (error != null) {
                    JOptionPane.showMessageDialog(SFTPPanel.this, "Error: " + error, "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                remotePath = path;
                remoteListModel.clear();
                remotePathCombo.removeAllItems();
                remotePathCombo.addItem(remotePath);

                for (FileItem item : items) {
                    remoteListModel.addElement(item);
                }

                remoteInfoLabel.setText("Remote System : " + fileCount + " files (" + formatSize(totalSize) + ")");
            }
        };
        worker.execute();
    }

    private void uploadSelected() {
        if (!remoteReady()) return;
        List<FileItem> selected = localList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Filter out parent directory
        List<File> filesToUpload = new ArrayList<>();
        for (FileItem item : selected) {
            if (item.isParent()) continue;
            String name = item.getRealName();
            filesToUpload.add(new File(localPath, name));
        }

        if (filesToUpload.isEmpty()) return;

        transferFiles(filesToUpload, remotePath, true);
    }

    private void downloadSelected() {
        if (!remoteReady()) return;
        List<FileItem> selected = remoteList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Filter out parent directory
        List<String> filesToDownload = new ArrayList<>();
        for (FileItem item : selected) {
            if (item.isParent()) continue;
            filesToDownload.add(item.getRealName());
        }

        if (filesToDownload.isEmpty()) return;

        // Capture the directory on the EDT: the worker must not read remotePath,
        // which changes if the user navigates while the download runs
        final String remoteDir = remotePath;
        transferFilesFromRemote(filesToDownload, remoteDir, localPath);
    }

    private void transferFiles(List<File> localFiles, String remoteDir, boolean upload) {
        if (!beginRemoteOp()) return;
        final SftpClient client = sftpClient;
        progressBar.setValue(0);
        progressBar.setString("Preparing...");

        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            private int totalFiles = 0;
            private int processedFiles = 0;

            @Override
            protected Void doInBackground() throws Exception {
                // Count total files
                for (File file : localFiles) {
                    totalFiles += countFiles(file);
                }

                for (File file : localFiles) {
                    uploadFileOrDirectory(file, remoteDir);
                }
                return null;
            }

            private int countFiles(File file) {
                if (file.isFile()) return 1;
                int count = 0;
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        count += countFiles(child);
                    }
                }
                return count;
            }

            private void uploadFileOrDirectory(File file, String remoteDirPath) throws IOException {
                String rPath = joinRemote(remoteDirPath, file.getName());

                if (file.isDirectory()) {
                    // Create remote directory
                    try {
                        client.mkdir(rPath);
                    } catch (IOException e) {
                        // Directory might already exist
                    }

                    // Upload contents
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            uploadFileOrDirectory(child, rPath);
                        }
                    }
                } else {
                    // Upload file
                    publish(++processedFiles);
                    try (InputStream is = new FileInputStream(file);
                         OutputStream os = client.write(rPath)) {
                        byte[] buf = new byte[JSSHConst.SFTP_BUFFER_SIZE];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                }
            }

            @Override
            protected void process(List<Integer> chunks) {
                int current = chunks.get(chunks.size() - 1);
                int percent = totalFiles > 0 ? (current * 100 / totalFiles) : 0;
                progressBar.setValue(percent);
                progressBar.setString("Uploading " + current + "/" + totalFiles);
            }

            @Override
            protected void done() {
                endRemoteOp();
                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("Upload complete");
                    loadRemoteDirectory(remotePath);
                } catch (Exception e) {
                    String msg = causeMessage(e);
                    progressBar.setString("Error: " + msg);
                    JOptionPane.showMessageDialog(SFTPPanel.this,
                        "Upload failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /**
     * Downloads {@code remoteFiles} (names relative to {@code remoteDir}) into
     * {@code localDir}. Both directories are fixed for the whole transfer.
     */
    private void transferFilesFromRemote(List<String> remoteFiles, String remoteDir, String localDir) {
        if (!beginRemoteOp()) return;
        final SftpClient client = sftpClient;
        progressBar.setValue(0);
        progressBar.setString("Preparing...");

        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            private int totalFiles = 0;
            private int processedFiles = 0;

            @Override
            protected Void doInBackground() throws Exception {
                // Count total files
                for (String name : remoteFiles) {
                    totalFiles += countRemoteFiles(joinRemote(remoteDir, name));
                }

                for (String name : remoteFiles) {
                    downloadFileOrDirectory(joinRemote(remoteDir, name), localDir);
                }
                return null;
            }

            private int countRemoteFiles(String path) throws IOException {
                Attributes attrs = client.stat(path);
                if (attrs.isRegularFile()) return 1;

                int count = 0;
                for (DirEntry entry : client.readDir(path)) {
                    String name = entry.getFilename();
                    if (name.equals(".") || name.equals("..")) continue;
                    count += countRemoteFiles(path + "/" + name);
                }
                return count;
            }

            private void downloadFileOrDirectory(String rPath, String localDirPath) throws IOException {
                Attributes attrs = client.stat(rPath);
                String name = rPath.substring(rPath.lastIndexOf('/') + 1);
                File localFile = new File(localDirPath, name);

                if (attrs.isDirectory()) {
                    // Create local directory
                    localFile.mkdirs();

                    // Download contents
                    for (DirEntry entry : client.readDir(rPath)) {
                        String childName = entry.getFilename();
                        if (childName.equals(".") || childName.equals("..")) continue;
                        downloadFileOrDirectory(rPath + "/" + childName, localFile.getAbsolutePath());
                    }
                } else {
                    // Download file
                    publish(++processedFiles);
                    try (InputStream is = client.read(rPath);
                         OutputStream os = new FileOutputStream(localFile)) {
                        byte[] buf = new byte[JSSHConst.SFTP_BUFFER_SIZE];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                }
            }

            @Override
            protected void process(List<Integer> chunks) {
                int current = chunks.get(chunks.size() - 1);
                int percent = totalFiles > 0 ? (current * 100 / totalFiles) : 0;
                progressBar.setValue(percent);
                progressBar.setString("Downloading " + current + "/" + totalFiles);
            }

            @Override
            protected void done() {
                endRemoteOp();
                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("Download complete");
                    loadLocalDirectory(localPath);
                } catch (Exception e) {
                    String msg = causeMessage(e);
                    progressBar.setString("Error: " + msg);
                    JOptionPane.showMessageDialog(SFTPPanel.this,
                        "Download failed: " + msg, "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // Local operations
    private void browseLocalDir() {
        JFileChooser chooser = new JFileChooser(localPath);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Local Directory");

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected != null && selected.isDirectory()) {
                loadLocalDirectory(selected.getAbsolutePath());
            }
        }
    }

    private void createLocalDir() {
        String name = JOptionPane.showInputDialog(this, "Folder name:");
        if (name != null && !name.trim().isEmpty()) {
            File newDir = new File(localPath, name.trim());
            if (newDir.mkdir()) {
                loadLocalDirectory(localPath);
            } else {
                JOptionPane.showMessageDialog(this, "Failed to create directory", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void renameLocal() {
        FileItem item = localList.getSelectedValue();
        if (item == null || item.isParent()) return;

        String oldName = item.getRealName();
        String newName = JOptionPane.showInputDialog(this, "New name:", oldName);
        if (newName != null && !newName.trim().isEmpty() && !newName.equals(oldName)) {
            File oldFile = new File(localPath, oldName);
            File newFile = new File(localPath, newName.trim());
            if (oldFile.renameTo(newFile)) {
                loadLocalDirectory(localPath);
            } else {
                JOptionPane.showMessageDialog(this, "Failed to rename", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void deleteLocal() {
        List<FileItem> selected = localList.getSelectedValuesList();
        if (selected.isEmpty()) return;

        int count = 0;
        for (FileItem item : selected) {
            if (!item.isParent()) count++;
        }

        if (count == 0) return;

        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete " + count + " item(s)?", "Confirm Delete", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            for (FileItem item : selected) {
                if (item.isParent()) continue;
                String name = item.getRealName();
                File file = new File(localPath, name);
                deleteRecursive(file);
            }
            loadLocalDirectory(localPath);
        }
    }

    private void deleteRecursive(File file) {
        // Never recurse through a symlink - deleting the link must not touch its target
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // Remote operations
    private void browseRemoteDir() {
        if (!remoteReady()) return;
        // Create a dialog to browse remote directories
        RemoteDirChooser chooser = new RemoteDirChooser(
            SwingUtilities.getWindowAncestor(this), sftpClient, remotePath);
        chooser.setVisible(true);

        String selected = chooser.getSelectedPath();
        if (selected != null) {
            loadRemoteDirectory(selected);
        }
    }

    private void createRemoteDir() {
        if (!remoteReady()) return;
        String name = JOptionPane.showInputDialog(this, "Folder name:");
        if (name != null && !name.trim().isEmpty()) {
            final String dir = remotePath;
            final String newPath = joinRemote(dir, name.trim());
            runRemoteOp(client -> client.mkdir(newPath), failure -> {
                if (failure == null) {
                    loadRemoteDirectory(dir);
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to create directory: " + causeMessage(failure),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
        }
    }

    private void renameRemote() {
        if (!remoteReady()) return;
        FileItem item = remoteList.getSelectedValue();
        if (item == null || item.isParent()) return;

        String oldName = item.getRealName();
        String newName = JOptionPane.showInputDialog(this, "New name:", oldName);
        if (newName != null && !newName.trim().isEmpty() && !newName.equals(oldName)) {
            final String dir = remotePath;
            final String oldPath = joinRemote(dir, oldName);
            final String newPath = joinRemote(dir, newName.trim());
            runRemoteOp(client -> client.rename(oldPath, newPath), failure -> {
                if (failure == null) {
                    loadRemoteDirectory(dir);
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to rename: " + causeMessage(failure),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
        }
    }

    private void deleteRemote() {
        if (!remoteReady()) return;
        List<FileItem> selected = remoteList.getSelectedValuesList();
        if (selected.isEmpty()) return;

        final List<String> paths = new ArrayList<>();
        final String dir = remotePath;
        for (FileItem item : selected) {
            if (!item.isParent()) paths.add(joinRemote(dir, item.getRealName()));
        }

        if (paths.isEmpty()) return;

        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete " + paths.size() + " item(s)?", "Confirm Delete", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            // Every item is attempted; failures are collected and reported together
            runRemoteOp(client -> {
                List<String> failures = new ArrayList<>();
                for (String path : paths) {
                    try {
                        deleteRemoteRecursive(client, path);
                    } catch (IOException e) {
                        failures.add(e.getMessage());
                    }
                }
                if (!failures.isEmpty()) {
                    throw new IOException(String.join("\n", failures));
                }
            }, failure -> {
                if (failure != null) {
                    JOptionPane.showMessageDialog(this, "Failed to delete: " + causeMessage(failure),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
                loadRemoteDirectory(dir);
            });
        }
    }

    /** Worker-thread only. */
    private static void deleteRemoteRecursive(SftpClient client, String path) throws IOException {
        // Use lstat so a symlink is seen as a link, not as its target;
        // a symlink (even to a directory) is deleted as the link itself
        Attributes attrs = client.lstat(path);
        if (attrs.isSymbolicLink()) {
            client.remove(path);
        } else if (attrs.isDirectory()) {
            for (DirEntry entry : client.readDir(path)) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                deleteRemoteRecursive(client, path + "/" + name);
            }
            client.rmdir(path);
        } else {
            client.remove(path);
        }
    }

    // Utility methods

    /** Joins a remote directory and an entry name without doubling the root slash. */
    static String joinRemote(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }

    /** Parent of an absolute remote path; the root is its own parent. */
    static String getParentPath(String path) {
        if (path.equals("/")) return "/";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }

    static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.0f kB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.0f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    public void setOnClose(Runnable onCloseAction) {
        this.onCloseAction = onCloseAction;
    }

    public void close() {
        closed = true;
        SftpClient client = sftpClient;
        sftpClient = null;
        try {
            if (client != null) {
                client.close();
            }
        } catch (IOException e) { }
    }

    // File item class
    /**
     * One row of a file list. {@code realName} is the exact filesystem name and is
     * the only thing used for path building; {@code name} is the decorated label
     * shown in the list: {@code [dir]}, {@code [@symlinkdir]}, {@code file (12 kB)}.
     * The label is never parsed back into a name (a real directory called
     * {@code @types} would otherwise be mistaken for a symlink named {@code types}).
     */
    static class FileItem {
        static final String PARENT = "..";

        final String realName;
        final String name;
        final boolean isDirectory;
        final boolean isSymlink;
        final long size;

        FileItem(String realName, boolean isDirectory, boolean isSymlink, long size) {
            this.realName = realName;
            this.isDirectory = isDirectory;
            this.isSymlink = isSymlink;
            this.size = size;
            this.name = buildLabel(realName, isDirectory, isSymlink, size);
        }

        static FileItem parent() {
            return new FileItem(PARENT, true, false, 0);
        }

        private static String buildLabel(String realName, boolean isDirectory, boolean isSymlink, long size) {
            if (isDirectory) {
                if (PARENT.equals(realName)) {
                    return "[..]";
                }
                return isSymlink ? "[@" + realName + "]" : "[" + realName + "]";
            }
            return realName + " (" + formatSize(size) + ")";
        }

        boolean isParent() {
            return PARENT.equals(realName);
        }

        String getRealName() {
            return realName;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Custom cell renderer
    private static class FileListCellRenderer extends DefaultListCellRenderer {
        private static final Color LINK_COLOR = new Color(0, 100, 180);

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof FileItem) {
                FileItem item = (FileItem) value;
                if (item.isDirectory) {
                    if (item.isSymlink) {
                        // Symlink to directory: bold + italic + blue
                        setFont(getFont().deriveFont(Font.BOLD | Font.ITALIC));
                        if (!isSelected) {
                            setForeground(LINK_COLOR);
                        }
                    } else {
                        setFont(getFont().deriveFont(Font.BOLD));
                    }
                } else {
                    if (item.isSymlink) {
                        // Symlink to file: italic + blue
                        setFont(getFont().deriveFont(Font.ITALIC));
                        if (!isSelected) {
                            setForeground(LINK_COLOR);
                        }
                    } else {
                        setFont(getFont().deriveFont(Font.PLAIN));
                    }
                }
            }

            return this;
        }
    }

    // Remote directory chooser dialog
    private static class RemoteDirChooser extends JDialog {
        private SftpClient sftpClient;
        private String currentPath;
        private String selectedPath = null;
        /** EDT-only: a listing is in flight; further navigation is ignored until it lands. */
        private boolean loading;

        private JTextField pathField;
        private JList<FileItem> dirList;
        private DefaultListModel<FileItem> dirListModel;
        private JButton goBtn;
        private JButton selectBtn;

        public RemoteDirChooser(Window owner, SftpClient sftpClient, String initialPath) {
            super(owner, "Select Remote Directory", ModalityType.APPLICATION_MODAL);
            this.sftpClient = sftpClient;
            this.currentPath = initialPath;

            initUI();
            loadDirectory(currentPath);

            setSize(400, 400);
            setLocationRelativeTo(owner);
        }

        private void initUI() {
            setLayout(new BorderLayout(5, 5));
            ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Path field at top
            JPanel topPanel = new JPanel(new BorderLayout(5, 0));
            topPanel.add(new JLabel("Path:"), BorderLayout.WEST);
            pathField = new JTextField(currentPath);
            pathField.addActionListener(e -> loadDirectory(pathField.getText().trim()));
            topPanel.add(pathField, BorderLayout.CENTER);

            goBtn = new JButton("Go");
            goBtn.addActionListener(e -> loadDirectory(pathField.getText().trim()));
            topPanel.add(goBtn, BorderLayout.EAST);

            add(topPanel, BorderLayout.NORTH);

            // Directory list
            dirListModel = new DefaultListModel<>();
            dirList = new JList<>(dirListModel);
            dirList.setCellRenderer(new FileListCellRenderer());
            dirList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            dirList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        FileItem selected = dirList.getSelectedValue();
                        if (selected != null) {
                            if (selected.isParent()) {
                                loadDirectory(getParentPath(currentPath));
                            } else {
                                loadDirectory(joinRemote(currentPath, selected.getRealName()));
                            }
                        }
                    }
                }
            });

            add(new JScrollPane(dirList), BorderLayout.CENTER);

            // Buttons
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

            selectBtn = new JButton("Select");
            selectBtn.addActionListener(e -> {
                selectedPath = currentPath;
                dispose();
            });
            btnPanel.add(selectBtn);

            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.addActionListener(e -> dispose());
            btnPanel.add(cancelBtn);

            add(btnPanel, BorderLayout.SOUTH);
        }

        /** Lists the directories under {@code path} off the EDT; ignored while a listing is already running. */
        private void loadDirectory(String path) {
            if (loading) return;
            loading = true;
            goBtn.setEnabled(false);
            selectBtn.setEnabled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            final SftpClient client = sftpClient;

            SwingWorker<List<FileItem>, Void> worker = new SwingWorker<List<FileItem>, Void>() {
                @Override
                protected List<FileItem> doInBackground() throws Exception {
                    // Directories only - including symlinks that point at one,
                    // which readDir reports as links, not directories
                    List<FileItem> items = listRemoteDirectory(client, path, true, null, null);
                    // Parent entry (if any) stays first; the rest by name
                    List<FileItem> dirs = new ArrayList<>();
                    FileItem parent = null;
                    for (FileItem item : items) {
                        if (item.isParent()) parent = item; else dirs.add(item);
                    }
                    dirs.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getRealName(), b.getRealName()));
                    if (parent != null) dirs.add(0, parent);
                    return dirs;
                }

                @Override
                protected void done() {
                    loading = false;
                    goBtn.setEnabled(true);
                    selectBtn.setEnabled(true);
                    setCursor(Cursor.getDefaultCursor());
                    List<FileItem> dirs;
                    try {
                        dirs = get();
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(RemoteDirChooser.this, "Error: " + causeMessage(e),
                            "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    dirListModel.clear();
                    for (FileItem dir : dirs) {
                        dirListModel.addElement(dir);
                    }
                    currentPath = path;
                    pathField.setText(path);
                }
            };
            worker.execute();
        }

        public String getSelectedPath() {
            return selectedPath;
        }
    }
}
