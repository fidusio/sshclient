package io.xlogistx.jssh.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * SSH key manager dialog for generating and managing SSH keys
 */
public class KeyManagerDialog extends JDialog {
    
    private JTable keyTable;
    private DefaultTableModel tableModel;
    private String sshDir;
    
    public KeyManagerDialog(Frame owner) {
        super(owner, "SSH Key Manager", true);
        
        sshDir = System.getProperty("user.home") + File.separator + ".ssh";
        
        initUI();
        loadKeys();
        
        setSize(600, 400);
        setLocationRelativeTo(owner);
    }
    
    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Table
        tableModel = new DefaultTableModel(
            new String[] { "Name", "Type", "Fingerprint" }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        keyTable = new JTable(tableModel);
        keyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keyTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        keyTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        keyTable.getColumnModel().getColumn(2).setPreferredWidth(300);
        
        add(new JScrollPane(keyTable), BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton generateBtn = new JButton("Generate Key...");
        generateBtn.addActionListener(e -> generateKey());
        buttonPanel.add(generateBtn);
        
        JButton importBtn = new JButton("Import...");
        importBtn.addActionListener(e -> importKey());
        buttonPanel.add(importBtn);
        
        JButton exportBtn = new JButton("Export Public Key");
        exportBtn.addActionListener(e -> exportPublicKey());
        buttonPanel.add(exportBtn);
        
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteKey());
        buttonPanel.add(deleteBtn);
        
        add(buttonPanel, BorderLayout.NORTH);
        
        // Info label and close button
        JPanel bottomPanel = new JPanel(new BorderLayout());
        JLabel infoLabel = new JLabel("Keys are stored in: " + sshDir);
        bottomPanel.add(infoLabel, BorderLayout.WEST);
        
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        
        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        closePanel.add(closeBtn);
        bottomPanel.add(closePanel, BorderLayout.EAST);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private void loadKeys() {
        tableModel.setRowCount(0);
        
        File dir = new File(sshDir);
        if (!dir.exists()) {
            return;
        }
        
        File[] files = dir.listFiles((d, name) -> 
            name.startsWith("id_") && !name.endsWith(".pub"));
        
        if (files == null) return;
        
        for (File file : files) {
            String name = file.getName();
            String type = getKeyType(name);
            String fingerprint = getFingerprint(file);
            
            tableModel.addRow(new Object[] { name, type, fingerprint });
        }
    }
    
    private String getKeyType(String filename) {
        if (filename.contains("ed25519")) return "Ed25519";
        if (filename.contains("ecdsa")) return "ECDSA";
        if (filename.contains("rsa")) return "RSA";
        if (filename.contains("dsa")) return "DSA";
        return "Unknown";
    }
    
    private String getFingerprint(File keyFile) {
        try {
            File pubFile = new File(keyFile.getPath() + ".pub");
            if (!pubFile.exists()) {
                return "No public key";
            }
            
            String content = Files.readString(pubFile.toPath()).trim();
            String[] parts = content.split("\\s+");
            if (parts.length >= 2) {
                byte[] keyData = Base64.getDecoder().decode(parts[1]);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(keyData);
                return "SHA256:" + Base64.getEncoder().encodeToString(digest)
                                         .replace("=", "")
                                         .substring(0, 43);
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }
    
    private void generateKey() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        // Key type
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Key Type:"), gbc);
        
        gbc.gridx = 1;
        JComboBox<String> typeCombo = new JComboBox<>(new String[] {
            "Ed25519 (recommended)", "ECDSA (nistp256)", "RSA (4096 bits)"
        });
        panel.add(typeCombo, gbc);
        
        // Filename
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Filename:"), gbc);
        
        gbc.gridx = 1;
        JTextField nameField = new JTextField("id_ed25519", 20);
        panel.add(nameField, gbc);
        
        // Passphrase
        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Passphrase:"), gbc);
        
        gbc.gridx = 1;
        JPasswordField passField = new JPasswordField(20);
        panel.add(passField, gbc);
        
        // Confirm passphrase
        gbc.gridx = 0; gbc.gridy = 3;
        panel.add(new JLabel("Confirm:"), gbc);
        
        gbc.gridx = 1;
        JPasswordField confirmField = new JPasswordField(20);
        panel.add(confirmField, gbc);
        
        // Comment
        gbc.gridx = 0; gbc.gridy = 4;
        panel.add(new JLabel("Comment:"), gbc);
        
        gbc.gridx = 1;
        JTextField commentField = new JTextField(System.getProperty("user.name") + "@" + getHostname(), 20);
        panel.add(commentField, gbc);
        
        // Update filename based on type
        typeCombo.addActionListener(e -> {
            String type = (String) typeCombo.getSelectedItem();
            if (type.startsWith("Ed25519")) nameField.setText("id_ed25519");
            else if (type.startsWith("ECDSA")) nameField.setText("id_ecdsa");
            else nameField.setText("id_rsa");
        });
        
        int result = JOptionPane.showConfirmDialog(this, panel, 
            "Generate SSH Key", JOptionPane.OK_CANCEL_OPTION);
        
        if (result != JOptionPane.OK_OPTION) return;
        
        String pass = new String(passField.getPassword());
        String confirm = new String(confirmField.getPassword());
        
        if (!pass.equals(confirm)) {
            JOptionPane.showMessageDialog(this, "Passphrases do not match", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String filename = nameField.getText().trim();
        String comment = commentField.getText().trim();
        String type = (String) typeCombo.getSelectedItem();
        
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        
        try {
            // Create .ssh directory if needed
            File dir = new File(sshDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File privateFile = new File(sshDir, filename);
            File publicFile = new File(sshDir, filename + ".pub");
            
            if (privateFile.exists()) {
                int overwrite = JOptionPane.showConfirmDialog(this,
                    "Key already exists. Overwrite?",
                    "Confirm",
                    JOptionPane.YES_NO_OPTION);
                if (overwrite != JOptionPane.YES_OPTION) return;
            }
            
            // Generate key pair using ssh-keygen (most reliable)
            String keyType;
            if (type.startsWith("Ed25519")) keyType = "ed25519";
            else if (type.startsWith("ECDSA")) keyType = "ecdsa";
            else keyType = "rsa";
            
            ProcessBuilder pb = new ProcessBuilder(
                "ssh-keygen", "-t", keyType, "-f", privateFile.getAbsolutePath(),
                "-N", pass, "-C", comment
            );
            
            if (keyType.equals("rsa")) {
                pb.command().add("-b");
                pb.command().add("4096");
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("ssh-keygen failed with exit code " + exitCode);
            }
            
            loadKeys();
            
            JOptionPane.showMessageDialog(this,
                "Key generated successfully!\n\n" +
                "Private key: " + privateFile.getAbsolutePath() + "\n" +
                "Public key: " + publicFile.getAbsolutePath(),
                "Success",
                JOptionPane.INFORMATION_MESSAGE);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Failed to generate key: " + e.getMessage() + "\n\n" +
                "Make sure ssh-keygen is installed.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }
    
    private void importKey() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import SSH Key");
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File sourceFile = chooser.getSelectedFile();
            File destFile = new File(sshDir, sourceFile.getName());
            
            try {
                Files.copy(sourceFile.toPath(), destFile.toPath(), 
                    StandardCopyOption.REPLACE_EXISTING);
                
                // Copy public key if exists
                File sourcePub = new File(sourceFile.getPath() + ".pub");
                if (sourcePub.exists()) {
                    Files.copy(sourcePub.toPath(), 
                        new File(destFile.getPath() + ".pub").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                }
                
                // Set permissions
                destFile.setReadable(false, false);
                destFile.setReadable(true, true);
                destFile.setWritable(false, false);
                destFile.setWritable(true, true);
                
                loadKeys();
                
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to import key: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void exportPublicKey() {
        int row = keyTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a key", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String name = (String) tableModel.getValueAt(row, 0);
        File pubFile = new File(sshDir, name + ".pub");
        
        if (!pubFile.exists()) {
            JOptionPane.showMessageDialog(this, "Public key file not found", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            String content = Files.readString(pubFile.toPath());
            
            JTextArea textArea = new JTextArea(content);
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(500, 150));
            
            JPanel panel = new JPanel(new BorderLayout(5, 5));
            panel.add(new JLabel("Public key (copy this to authorized_keys):"), BorderLayout.NORTH);
            panel.add(scrollPane, BorderLayout.CENTER);
            
            JButton copyBtn = new JButton("Copy to Clipboard");
            copyBtn.addActionListener(e -> {
                textArea.selectAll();
                textArea.copy();
                JOptionPane.showMessageDialog(panel, "Copied to clipboard!");
            });
            
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnPanel.add(copyBtn);
            panel.add(btnPanel, BorderLayout.SOUTH);
            
            JOptionPane.showMessageDialog(this, panel, "Public Key", 
                JOptionPane.PLAIN_MESSAGE);
            
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to read public key: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void deleteKey() {
        int row = keyTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a key",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String name = (String) tableModel.getValueAt(row, 0);
        
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete key '" + name + "'?\n\nThis cannot be undone!",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            File privateFile = new File(sshDir, name);
            File publicFile = new File(sshDir, name + ".pub");
            
            privateFile.delete();
            publicFile.delete();
            
            loadKeys();
        }
    }
    
    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
