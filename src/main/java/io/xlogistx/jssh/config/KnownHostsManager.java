package io.xlogistx.jssh.config;

import java.io.*;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;

/**
 * Manages known SSH host keys, similar to OpenSSH's known_hosts file.
 * Stores host keys in ~/.jssh/known_hosts
 */
public class KnownHostsManager {

    private static KnownHostsManager instance;

    private Path knownHostsPath;
    private Map<String, HostEntry> knownHosts = new LinkedHashMap<>();

    public enum VerifyResult {
        /** Host key is known and matches */
        KNOWN_OK,
        /** Host key is known but has changed (potential security issue) */
        KNOWN_CHANGED,
        /** Host is not in known hosts */
        UNKNOWN
    }

    public static class HostEntry {
        private String hostKey;  // [host]:port format
        private String keyType;
        private String fingerprint;
        private String encodedKey;

        public HostEntry(String hostKey, String keyType, String fingerprint, String encodedKey) {
            this.hostKey = hostKey;
            this.keyType = keyType;
            this.fingerprint = fingerprint;
            this.encodedKey = encodedKey;
        }

        public String getHostKey() { return hostKey; }
        public String getKeyType() { return keyType; }
        public String getFingerprint() { return fingerprint; }
        public String getEncodedKey() { return encodedKey; }
    }

    private KnownHostsManager() {
        String home = System.getProperty("user.home");
        Path configDir = Paths.get(home, JSSHConst.CONFIG_DIR);
        knownHostsPath = configDir.resolve(JSSHConst.KNOWN_HOSTS_FILE);

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            System.err.println("Failed to create config directory: " + e.getMessage());
        }

        load();
    }

    public static synchronized KnownHostsManager getInstance() {
        if (instance == null) {
            instance = new KnownHostsManager();
        }
        return instance;
    }

    /**
     * Create a host key string from host and port
     */
    private String makeHostKey(String host, int port) {
        if (port == JSSHConst.DEFAULT_SSH_PORT) {
            return host.toLowerCase();
        }
        return "[" + host.toLowerCase() + "]:" + port;
    }

    /**
     * Check if a host key is known and matches
     */
    public VerifyResult verify(String host, int port, String fingerprint, PublicKey key) {
        String hostKey = makeHostKey(host, port);
        HostEntry entry = knownHosts.get(hostKey);

        if (entry == null) {
            return VerifyResult.UNKNOWN;
        }

        if (entry.getFingerprint().equals(fingerprint)) {
            return VerifyResult.KNOWN_OK;
        }

        // The stored fingerprint may come from the old scheme (hash of the X.509
        // encoding instead of the SSH wire encoding). The raw key bytes are stored
        // too - if they match, it is the same key: migrate the entry to the new
        // fingerprint silently instead of raising a false "key changed" alarm.
        if (key != null && entry.getEncodedKey() != null && !entry.getEncodedKey().isEmpty()
                && entry.getEncodedKey().equals(Base64.getEncoder().encodeToString(key.getEncoded()))) {
            addHost(host, port, entry.getKeyType(), fingerprint, key);
            return VerifyResult.KNOWN_OK;
        }

        return VerifyResult.KNOWN_CHANGED;
    }

    /**
     * Get the stored fingerprint for a host
     */
    public String getStoredFingerprint(String host, int port) {
        String hostKey = makeHostKey(host, port);
        HostEntry entry = knownHosts.get(hostKey);
        return entry != null ? entry.getFingerprint() : null;
    }

    /**
     * Add or update a known host entry
     */
    public void addHost(String host, int port, String keyType, String fingerprint, PublicKey key) {
        String hostKey = makeHostKey(host, port);
        String encodedKey = Base64.getEncoder().encodeToString(key.getEncoded());

        HostEntry entry = new HostEntry(hostKey, keyType, fingerprint, encodedKey);
        knownHosts.put(hostKey, entry);

        save();
    }

    /**
     * Remove a known host entry
     */
    public void removeHost(String host, int port) {
        String hostKey = makeHostKey(host, port);
        knownHosts.remove(hostKey);
        save();
    }

    /**
     * Get all known hosts
     */
    public List<HostEntry> getAllHosts() {
        return new ArrayList<>(knownHosts.values());
    }

    /**
     * Load known hosts from file
     */
    private void load() {
        knownHosts.clear();

        if (!Files.exists(knownHostsPath)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(knownHostsPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Format: hostkey keytype fingerprint encodedkey
                String[] parts = line.split("\\s+", 4);
                if (parts.length >= 3) {
                    String hostKey = parts[0];
                    String keyType = parts[1];
                    String fingerprint = parts[2];
                    String encodedKey = parts.length > 3 ? parts[3] : "";

                    HostEntry entry = new HostEntry(hostKey, keyType, fingerprint, encodedKey);
                    knownHosts.put(hostKey, entry);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load known hosts: " + e.getMessage());
        }
    }

    /**
     * Save known hosts to file
     */
    private void save() {
        try (BufferedWriter writer = Files.newBufferedWriter(knownHostsPath)) {
            writer.write("# JSSH Known Hosts File");
            writer.newLine();
            writer.write("# Format: hostkey keytype fingerprint encodedkey");
            writer.newLine();
            writer.newLine();

            for (HostEntry entry : knownHosts.values()) {
                writer.write(entry.getHostKey());
                writer.write(" ");
                writer.write(entry.getKeyType());
                writer.write(" ");
                writer.write(entry.getFingerprint());
                if (entry.getEncodedKey() != null && !entry.getEncodedKey().isEmpty()) {
                    writer.write(" ");
                    writer.write(entry.getEncodedKey());
                }
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to save known hosts: " + e.getMessage());
        }
    }

    /**
     * Calculate OpenSSH-style fingerprint from public key (SHA-256 over the
     * SSH wire encoding, matching `ssh-keygen -lf`)
     */
    public static String calculateFingerprint(PublicKey key) {
        try {
            return org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
        } catch (Exception e) {
            return "unknown";
        }
    }
}