package io.xlogistx.jssh.ui;

import io.xlogistx.jssh.config.JSSHConst;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.io.output.SecureByteArrayOutputStream;
import org.zoxweb.server.io.IOUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * SSH key manager dialog for generating and managing SSH keys
 */
public class KeyManagerDialog extends JDialog {
    
    private JTable keyTable;
    private DefaultTableModel tableModel;
    private String sshDir;
    
    public KeyManagerDialog(Frame owner) {
        super(owner, "SSH Key Manager", true);

        sshDir = System.getProperty("user.home") + File.separator + JSSHConst.SSH_DIR;

        initUI();
        loadKeys();

        setSize(JSSHConst.KEY_MANAGER_WIDTH, JSSHConst.KEY_MANAGER_HEIGHT);
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

            String content = IOUtil.pathToString(pubFile.toPath()).trim();
            String[] parts = content.split("\\s+");
            if (parts.length >= 2) {
                byte[] keyData = Base64.getDecoder().decode(parts[1]);
                MessageDigest md = MessageDigest.getInstance(JSSHConst.FINGERPRINT_ALGORITHM);
                byte[] digest = md.digest(keyData);
                return JSSHConst.FINGERPRINT_PREFIX + Base64.getEncoder().encodeToString(digest)
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
        JTextField nameField = new JTextField(JSSHConst.DEFAULT_KEY_NAME, 20);
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

        // Passphrase stays a char[] so it can be wiped once the key is written
        final char[] pass = passField.getPassword();
        char[] confirm = confirmField.getPassword();
        boolean match = Arrays.equals(pass, confirm);
        Arrays.fill(confirm, '\0');

        if (!match) {
            Arrays.fill(pass, '\0');
            JOptionPane.showMessageDialog(this, "Passphrases do not match",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String filename = nameField.getText().trim();
        final String comment = commentField.getText().trim();
        String type = (String) typeCombo.getSelectedItem();

        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\")) {
            Arrays.fill(pass, '\0');
            JOptionPane.showMessageDialog(this, "Please enter a plain file name (no directories)",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Create .ssh directory if needed
        File dir = new File(sshDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        final File privateFile = new File(sshDir, filename);
        final File publicFile = new File(sshDir, filename + ".pub");

        if (privateFile.exists() || publicFile.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                "Key already exists. Overwrite?",
                "Confirm",
                JOptionPane.YES_NO_OPTION);
            if (overwrite != JOptionPane.YES_OPTION) {
                Arrays.fill(pass, '\0');
                return;
            }
        }

        // Map the dialog choice to an OpenSSH key type name
        final String keyType;
        final int keyBits;
        if (type.startsWith("Ed25519")) {
            keyType = KeyPairProvider.SSH_ED25519;
            keyBits = ED25519_KEY_BITS;
        } else if (type.startsWith("ECDSA")) {
            keyType = KeyPairProvider.ECDSA_SHA2_NISTP256;
            keyBits = ECDSA_NISTP256_KEY_BITS;
        } else {
            keyType = KeyPairProvider.SSH_RSA;
            keyBits = JSSHConst.RSA_KEY_BITS;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        // Generate in-process (RSA-4096 takes a moment) off the EDT. Nothing
        // below touches Swing until done(); all inputs were snapshotted above.
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                generateKeyPairFiles(keyType, keyBits, privateFile.toPath(), comment, pass);
                return null;
            }

            @Override
            protected void done() {
                Arrays.fill(pass, '\0');
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    loadKeys();
                    JOptionPane.showMessageDialog(KeyManagerDialog.this,
                        "Key generated successfully!\n\n" +
                        "Private key: " + privateFile.getAbsolutePath() + "\n" +
                        "Public key: " + publicFile.getAbsolutePath(),
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                        ? e.getCause() : e;
                    JOptionPane.showMessageDialog(KeyManagerDialog.this,
                        "Failed to generate key: " + cause.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /** Ed25519 keys have a fixed size; the value is only informational to MINA. */
    static final int ED25519_KEY_BITS = 256;
    /** ECDSA nistp256 curve size. */
    static final int ECDSA_NISTP256_KEY_BITS = 256;

    /**
     * Generate an SSH key pair in-process and write it in OpenSSH format:
     * {@code privateFile} (openssh-key-v1, encrypted with aes256-ctr/bcrypt when
     * a passphrase is given) and {@code privateFile + ".pub"} (single-line
     * public key). Equivalent to {@code ssh-keygen -t <type> -b <bits> -f <file>}
     * without exposing the passphrase on a command line.
     * <p>
     * Headless-safe; does not touch Swing.
     *
     * @param keyType     OpenSSH key type name, e.g. {@link KeyPairProvider#SSH_ED25519},
     *                    {@link KeyPairProvider#ECDSA_SHA2_NISTP256}, {@link KeyPairProvider#SSH_RSA}
     * @param keyBits     key size in bits (RSA modulus / curve size; ignored for Ed25519)
     * @param privateFile destination private key path; existing files are replaced
     * @param comment     key comment (may be null/empty)
     * @param passphrase  passphrase, or null/empty for an unencrypted key. The caller
     *                    owns the array and should wipe it afterwards; this method
     *                    does not retain it.
     */
    public static void generateKeyPairFiles(String keyType, int keyBits, Path privateFile,
                                            String comment, char[] passphrase)
            throws IOException, GeneralSecurityException {
        Path publicFile = privateFile.resolveSibling(privateFile.getFileName() + ".pub");

        KeyPair keyPair = KeyUtils.generateKeyPair(keyType, keyBits);

        // Serialize both halves to memory first so a failure leaves no half-written files
        byte[] privateBytes;
        byte[] publicBytes;
        OpenSSHKeyEncryptionContext encryption = null;
        try {
            if (passphrase != null && passphrase.length > 0) {
                encryption = new OpenSSHKeyEncryptionContext();
                encryption.setCipherType("256");   // aes256-ctr, the ssh-keygen default
                // MINA's context only accepts a String; it is dropped right after writing
                encryption.setPassword(new String(passphrase));
            }
            try (SecureByteArrayOutputStream out = new SecureByteArrayOutputStream()) {
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, encryption, out);
                privateBytes = out.toByteArray();
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(keyPair, comment, out);
                out.write('\n');
                publicBytes = out.toByteArray();
            }
        } finally {
            if (encryption != null) {
                encryption.setPassword(null);
            }
        }

        try {
            // Private key: owner read/write only (0600) where the filesystem supports it
            Files.deleteIfExists(privateFile);
            if (privateFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.newByteChannel(privateFile,
                        EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(perms)).close();
                Files.setPosixFilePermissions(privateFile, perms);
            }
            Files.write(privateFile, privateBytes);
            if (!privateFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                // Best effort on non-POSIX (Windows): drop "everyone" access bits
                File f = privateFile.toFile();
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);
                f.setExecutable(false, false);
            }
            Files.write(publicFile, publicBytes);
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
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
            String content = IOUtil.pathToString(pubFile.toPath());
            
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
