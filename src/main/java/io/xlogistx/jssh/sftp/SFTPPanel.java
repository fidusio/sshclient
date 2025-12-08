package io.xlogistx.jssh.sftp;

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

/**
 * SFTP file browser panel - MindTerm style
 */
public class SFTPPanel extends JPanel {
    
    private SSHConnection connection;
    private SftpClient sftpClient;
    
    // Local side
    private String localPath;
    private JComboBox<String> localPathCombo;
    private JList<FileItem> localList;
    private DefaultListModel<FileItem> localListModel;
    private JLabel localInfoLabel;
    
    // Remote side
    private String remotePath = "/";
    private JComboBox<String> remotePathCombo;
    private JList<FileItem> remoteList;
    private DefaultListModel<FileItem> remoteListModel;
    private JLabel remoteInfoLabel;
    
    // Status
    private JLabel statusLabel;
    private JProgressBar progressBar;
    
    public SFTPPanel(SSHConnection connection) throws IOException {
        this.connection = connection;
        this.sftpClient = connection.openSftp();
        this.localPath = System.getProperty("user.home");
        
        // Try to get remote home directory
        try {
            remotePath = sftpClient.canonicalPath(".");
        } catch (Exception e) {
            remotePath = "/";
        }
        
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        initUI();
        
        // Load initial directories
        loadLocalDirectory(localPath);
        loadRemoteDirectory(remotePath);
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
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null) {
                window.dispose();
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
                        if (item.name.equals("[..]")) {
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
                        if (item.name.equals("[..]")) {
                            newPath = getParentPath(remotePath);
                        } else {
                            newPath = remotePath.equals("/") ? "/" + item.getRealName() : remotePath + "/" + item.getRealName();
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
        
        JButton mkDirBtn = new JButton("MkDir");
        mkDirBtn.addActionListener(e -> createRemoteDir());
        btnPanel.add(mkDirBtn);
        
        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> renameRemote());
        btnPanel.add(renameBtn);
        
        JPanel btnPanel2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteRemote());
        btnPanel2.add(deleteBtn);
        
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
        
        // Upload button (local to remote)
        JButton uploadBtn = new JButton("-->");
        uploadBtn.setToolTipText("Upload (Local to Remote)");
        uploadBtn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        uploadBtn.addActionListener(e -> uploadSelected());
        gbc.gridy = 1;
        panel.add(uploadBtn, gbc);
        
        return panel;
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
            localListModel.addElement(new FileItem("[..]", true, 0));
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
            boolean isDir = file.isDirectory();
            long size = file.length();
            
            if (isDir) {
                localListModel.addElement(new FileItem("[" + name + "]", true, 0));
            } else {
                localListModel.addElement(new FileItem(name + " (" + formatSize(size) + ")", false, size));
                totalSize += size;
            }
            fileCount++;
        }
        
        localInfoLabel.setText("Local System : " + fileCount + " files (" + formatSize(totalSize) + ")");
    }
    
    private void loadRemoteDirectory(String path) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            private List<FileItem> items = new ArrayList<>();
            private long totalSize = 0;
            private int fileCount = 0;
            private String error = null;
            
            @Override
            protected Void doInBackground() {
                try {
                    List<DirEntry> entries = new ArrayList<>();
                    for (DirEntry entry : sftpClient.readDir(path)) {
                        entries.add(entry);
                    }
                    
                    // Sort: directories first, then by name
                    entries.sort((a, b) -> {
                        boolean aDir = a.getAttributes().isDirectory();
                        boolean bDir = b.getAttributes().isDirectory();
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFilename().compareToIgnoreCase(b.getFilename());
                    });
                    
                    // Add parent entry
                    if (!path.equals("/")) {
                        items.add(new FileItem("[..]", true, 0));
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
                                String linkPath = path.equals("/") ? "/" + name : path + "/" + name;
                                Attributes targetAttrs = sftpClient.stat(linkPath);
                                if (targetAttrs.isDirectory()) {
                                    isDir = true;
                                }
                            } catch (Exception e) {
                                // Link target doesn't exist or can't be accessed
                            }
                        }

                        if (isDir) {
                            if (isSymlink) {
                                items.add(new FileItem("[@" + name + "]", true, true, 0));
                            } else {
                                items.add(new FileItem("[" + name + "]", true, false, 0));
                            }
                        } else {
                            items.add(new FileItem(name + " (" + formatSize(size) + ")", false, isSymlink, size));
                            totalSize += size;
                        }
                        fileCount++;
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
                return null;
            }
            
            @Override
            protected void done() {
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
        List<FileItem> selected = localList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Filter out parent directory
        List<File> filesToUpload = new ArrayList<>();
        for (FileItem item : selected) {
            if (item.name.equals("[..]")) continue;
            String name = item.getRealName();
            filesToUpload.add(new File(localPath, name));
        }
        
        if (filesToUpload.isEmpty()) return;
        
        transferFiles(filesToUpload, remotePath, true);
    }
    
    private void downloadSelected() {
        List<FileItem> selected = remoteList.getSelectedValuesList();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Filter out parent directory
        List<String> filesToDownload = new ArrayList<>();
        for (FileItem item : selected) {
            if (item.name.equals("[..]")) continue;
            filesToDownload.add(item.getRealName());
        }
        
        if (filesToDownload.isEmpty()) return;
        
        transferFilesFromRemote(filesToDownload, localPath);
    }
    
    private void transferFiles(List<File> localFiles, String remoteDir, boolean upload) {
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
                String rPath = remoteDirPath.equals("/") ? 
                    "/" + file.getName() : remoteDirPath + "/" + file.getName();
                
                if (file.isDirectory()) {
                    // Create remote directory
                    try {
                        sftpClient.mkdir(rPath);
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
                         OutputStream os = sftpClient.write(rPath)) {
                        byte[] buf = new byte[32768];
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
                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("Upload complete");
                    loadRemoteDirectory(remotePath);
                } catch (Exception e) {
                    progressBar.setString("Error: " + e.getMessage());
                    JOptionPane.showMessageDialog(SFTPPanel.this, 
                        "Upload failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
    
    private void transferFilesFromRemote(List<String> remoteFiles, String localDir) {
        progressBar.setValue(0);
        progressBar.setString("Preparing...");
        
        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            private int totalFiles = 0;
            private int processedFiles = 0;
            
            @Override
            protected Void doInBackground() throws Exception {
                // Count total files
                for (String name : remoteFiles) {
                    String rPath = remotePath.equals("/") ? "/" + name : remotePath + "/" + name;
                    totalFiles += countRemoteFiles(rPath);
                }
                
                for (String name : remoteFiles) {
                    String rPath = remotePath.equals("/") ? "/" + name : remotePath + "/" + name;
                    downloadFileOrDirectory(rPath, localDir);
                }
                return null;
            }
            
            private int countRemoteFiles(String path) throws IOException {
                Attributes attrs = sftpClient.stat(path);
                if (attrs.isRegularFile()) return 1;
                
                int count = 0;
                for (DirEntry entry : sftpClient.readDir(path)) {
                    String name = entry.getFilename();
                    if (name.equals(".") || name.equals("..")) continue;
                    count += countRemoteFiles(path + "/" + name);
                }
                return count;
            }
            
            private void downloadFileOrDirectory(String rPath, String localDirPath) throws IOException {
                Attributes attrs = sftpClient.stat(rPath);
                String name = rPath.substring(rPath.lastIndexOf('/') + 1);
                File localFile = new File(localDirPath, name);
                
                if (attrs.isDirectory()) {
                    // Create local directory
                    localFile.mkdirs();
                    
                    // Download contents
                    for (DirEntry entry : sftpClient.readDir(rPath)) {
                        String childName = entry.getFilename();
                        if (childName.equals(".") || childName.equals("..")) continue;
                        downloadFileOrDirectory(rPath + "/" + childName, localFile.getAbsolutePath());
                    }
                } else {
                    // Download file
                    publish(++processedFiles);
                    try (InputStream is = sftpClient.read(rPath);
                         OutputStream os = new FileOutputStream(localFile)) {
                        byte[] buf = new byte[32768];
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
                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("Download complete");
                    loadLocalDirectory(localPath);
                } catch (Exception e) {
                    progressBar.setString("Error: " + e.getMessage());
                    JOptionPane.showMessageDialog(SFTPPanel.this,
                        "Download failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
        if (item == null || item.name.equals("[..]")) return;
        
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
            if (!item.name.equals("[..]")) count++;
        }
        
        if (count == 0) return;
        
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete " + count + " item(s)?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        
        if (confirm == JOptionPane.YES_OPTION) {
            for (FileItem item : selected) {
                if (item.name.equals("[..]")) continue;
                String name = item.getRealName();
                File file = new File(localPath, name);
                deleteRecursive(file);
            }
            loadLocalDirectory(localPath);
        }
    }
    
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
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
        String name = JOptionPane.showInputDialog(this, "Folder name:");
        if (name != null && !name.trim().isEmpty()) {
            try {
                String newPath = remotePath.equals("/") ? "/" + name.trim() : remotePath + "/" + name.trim();
                sftpClient.mkdir(newPath);
                loadRemoteDirectory(remotePath);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to create directory: " + e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void renameRemote() {
        FileItem item = remoteList.getSelectedValue();
        if (item == null || item.name.equals("[..]")) return;
        
        String oldName = item.getRealName();
        String newName = JOptionPane.showInputDialog(this, "New name:", oldName);
        if (newName != null && !newName.trim().isEmpty() && !newName.equals(oldName)) {
            try {
                String oldPath = remotePath.equals("/") ? "/" + oldName : remotePath + "/" + oldName;
                String newPath = remotePath.equals("/") ? "/" + newName.trim() : remotePath + "/" + newName.trim();
                sftpClient.rename(oldPath, newPath);
                loadRemoteDirectory(remotePath);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to rename: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void deleteRemote() {
        List<FileItem> selected = remoteList.getSelectedValuesList();
        if (selected.isEmpty()) return;
        
        int count = 0;
        for (FileItem item : selected) {
            if (!item.name.equals("[..]")) count++;
        }
        
        if (count == 0) return;
        
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete " + count + " item(s)?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        
        if (confirm == JOptionPane.YES_OPTION) {
            for (FileItem item : selected) {
                if (item.name.equals("[..]")) continue;
                String name = item.getRealName();
                String path = remotePath.equals("/") ? "/" + name : remotePath + "/" + name;
                try {
                    deleteRemoteRecursive(path);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(this, "Failed to delete: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
            loadRemoteDirectory(remotePath);
        }
    }
    
    private void deleteRemoteRecursive(String path) throws IOException {
        Attributes attrs = sftpClient.stat(path);
        if (attrs.isDirectory()) {
            for (DirEntry entry : sftpClient.readDir(path)) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                deleteRemoteRecursive(path + "/" + name);
            }
            sftpClient.rmdir(path);
        } else {
            sftpClient.remove(path);
        }
    }
    
    // Utility methods
    private String getParentPath(String path) {
        if (path.equals("/")) return "/";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }
    
    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.0f kB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.0f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
    
    public void close() {
        try {
            if (sftpClient != null) {
                sftpClient.close();
            }
        } catch (IOException e) { }
    }
    
    // File item class
    private static class FileItem {
        String name;
        boolean isDirectory;
        boolean isSymlink;
        long size;

        FileItem(String name, boolean isDirectory, long size) {
            this(name, isDirectory, false, size);
        }

        FileItem(String name, boolean isDirectory, boolean isSymlink, long size) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.isSymlink = isSymlink;
            this.size = size;
        }

        String getRealName() {
            // Handle [..] and [dirname] and [@linkname]
            if (name.equals("[..]")) {
                return "..";
            }
            if (name.startsWith("[@") && name.endsWith("]")) {
                return name.substring(2, name.length() - 1);
            }
            if (name.startsWith("[") && name.endsWith("]")) {
                return name.substring(1, name.length() - 1);
            }
            // Remove size suffix for files: "filename (123 kB)"
            int parenIdx = name.lastIndexOf(" (");
            if (parenIdx > 0) {
                return name.substring(0, parenIdx);
            }
            return name;
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
        
        private JTextField pathField;
        private JList<String> dirList;
        private DefaultListModel<String> dirListModel;
        
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
            
            JButton goBtn = new JButton("Go");
            goBtn.addActionListener(e -> loadDirectory(pathField.getText().trim()));
            topPanel.add(goBtn, BorderLayout.EAST);
            
            add(topPanel, BorderLayout.NORTH);
            
            // Directory list
            dirListModel = new DefaultListModel<>();
            dirList = new JList<>(dirListModel);
            dirList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            dirList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        String selected = dirList.getSelectedValue();
                        if (selected != null) {
                            if (selected.equals("[..]")) {
                                String parent = currentPath.equals("/") ? "/" : 
                                    currentPath.substring(0, Math.max(1, currentPath.lastIndexOf('/')));
                                loadDirectory(parent);
                            } else {
                                String name = selected.substring(1, selected.length() - 1);
                                String newPath = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
                                loadDirectory(newPath);
                            }
                        }
                    }
                }
            });
            
            add(new JScrollPane(dirList), BorderLayout.CENTER);
            
            // Buttons
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            
            JButton selectBtn = new JButton("Select");
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
        
        private void loadDirectory(String path) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            dirListModel.clear();
            
            try {
                // Add parent entry
                if (!path.equals("/")) {
                    dirListModel.addElement("[..]");
                }
                
                // List directories only
                List<String> dirs = new ArrayList<>();
                for (DirEntry entry : sftpClient.readDir(path)) {
                    String name = entry.getFilename();
                    if (name.equals(".") || name.equals("..")) continue;
                    if (entry.getAttributes().isDirectory()) {
                        dirs.add(name);
                    }
                }
                
                Collections.sort(dirs, String.CASE_INSENSITIVE_ORDER);
                for (String dir : dirs) {
                    dirListModel.addElement("[" + dir + "]");
                }
                
                currentPath = path;
                pathField.setText(path);
                
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error: " + e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        }
        
        public String getSelectedPath() {
            return selectedPath;
        }
    }
}
