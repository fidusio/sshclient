package io.xlogistx.jssh.config;

import java.io.*;
import java.nio.file.*;
import java.security.PublicKey;
import java.util.*;

/**
 * Manages known SSH host keys, similar to OpenSSH's known_hosts file.
 * Stores host keys in ~/.jssh/known_hosts
 *
 * <p>Like OpenSSH, entries are kept per (host[:port], key type): a server may
 * legitimately present an RSA key on one connection and an ed25519 key on the
 * next (e.g. after a client/server algorithm-preference change), and that must
 * not be reported as a changed key. A key is reported as CHANGED only when an
 * entry of the <em>same</em> type exists with a different fingerprint; when no
 * entry of that type exists the host is UNKNOWN for that type.
 *
 * <p>File format (one entry per line): {@code hostkey keytype fingerprint [encodedkey]}.
 * Legacy lines without a key type ({@code hostkey fingerprint [encodedkey]}) are
 * still accepted; such an entry matches any key type and is rewritten with the
 * concrete type the first time it matches.
 */
public class KnownHostsManager {

    private static KnownHostsManager instance;

    /** Key type recorded for legacy entries that did not carry one; matches any type. */
    public static final String ANY_KEY_TYPE = "*";

    private final Path knownHostsPath;
    /** Keyed by {@link #entryKey(String, String)} = hostKey + TAB + normalized key type */
    private final Map<String, HostEntry> knownHosts = new LinkedHashMap<>();
    /**
     * Key type most recently passed to {@link #verify} for each host key, so the
     * type-less {@link #getStoredFingerprint(String, int)} can report the entry
     * the last verification was compared against.
     */
    private final Map<String, String> lastVerifiedType = new HashMap<>();

    public enum VerifyResult {
        /** Host key is known and matches */
        KNOWN_OK,
        /** Host key of this type is known but has changed (potential security issue) */
        KNOWN_CHANGED,
        /** No key of this type is known for the host */
        UNKNOWN
    }

    public static class HostEntry {
        private final String hostKey;  // host or [host]:port format
        private final String keyType;  // normalized (upper-case), or ANY_KEY_TYPE
        private final String fingerprint;
        private final String encodedKey;

        public HostEntry(String hostKey, String keyType, String fingerprint, String encodedKey) {
            this.hostKey = hostKey;
            this.keyType = normalizeKeyType(keyType);
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

    /**
     * Manager backed by an explicit file (for tests); the application uses
     * {@link #getInstance()}.
     */
    KnownHostsManager(Path knownHostsFile) {
        this.knownHostsPath = knownHostsFile;
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
    private static String makeHostKey(String host, int port) {
        if (port == JSSHConst.DEFAULT_SSH_PORT) {
            return host.toLowerCase();
        }
        return "[" + host.toLowerCase() + "]:" + port;
    }

    /**
     * Canonical form of a key type name: upper-case, with blank/unknown
     * placeholders mapped to {@link #ANY_KEY_TYPE}.
     */
    static String normalizeKeyType(String keyType) {
        if (keyType == null) {
            return ANY_KEY_TYPE;
        }
        String t = keyType.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty() || t.equals(ANY_KEY_TYPE) || t.equals("?") || t.equals("UNKNOWN")) {
            return ANY_KEY_TYPE;
        }
        return t;
    }

    private static String entryKey(String hostKey, String keyType) {
        return hostKey + "\t" + normalizeKeyType(keyType);
    }

    /**
     * Key type label for a public key, using the same names the connection code
     * reports to the host-key dialogs (RSA, ECDSA, ED25519, DSA).
     */
    public static String keyTypeOf(PublicKey key) {
        if (key == null) {
            return ANY_KEY_TYPE;
        }
        String alg = key.getAlgorithm();
        if (alg == null) {
            return ANY_KEY_TYPE;
        }
        switch (alg) {
            case "EdDSA":
            case "Ed25519":
                return "ED25519";
            case "EC":
                return "ECDSA";
            case "RSA":
                return "RSA";
            case "DSA":
                return "DSA";
            default:
                return normalizeKeyType(alg);
        }
    }

    /**
     * Check whether the given key is known for the host; the key type is derived
     * from the key itself.
     */
    public VerifyResult verify(String host, int port, String fingerprint, PublicKey key) {
        return verify(host, port, keyTypeOf(key), fingerprint, key);
    }

    /**
     * Check whether a host key of the given type is known and matches.
     *
     * @return {@link VerifyResult#KNOWN_OK} if an entry of this type (or a legacy
     *         type-less entry) has the same fingerprint, {@link VerifyResult#KNOWN_CHANGED}
     *         if such an entry exists with a different fingerprint, and
     *         {@link VerifyResult#UNKNOWN} if no entry of this type exists
     */
    public synchronized VerifyResult verify(String host, int port, String keyType, String fingerprint, PublicKey key) {
        String hostKey = makeHostKey(host, port);
        String type = normalizeKeyType(keyType);
        lastVerifiedType.put(hostKey, type);

        HostEntry entry = knownHosts.get(entryKey(hostKey, type));
        boolean legacy = false;
        if (entry == null && !ANY_KEY_TYPE.equals(type)) {
            // Legacy entry written without a key type: applies to any type
            entry = knownHosts.get(entryKey(hostKey, ANY_KEY_TYPE));
            legacy = entry != null;
        }

        if (entry == null) {
            return VerifyResult.UNKNOWN;
        }

        if (entry.getFingerprint().equals(fingerprint)) {
            if (legacy) {
                // Rewrite the type-less entry in the current per-type format
                knownHosts.remove(entryKey(hostKey, ANY_KEY_TYPE));
                knownHosts.put(entryKey(hostKey, type),
                        new HostEntry(hostKey, type, fingerprint, entry.getEncodedKey()));
                save();
            }
            return VerifyResult.KNOWN_OK;
        }

        // The stored fingerprint may come from the old scheme (hash of the X.509
        // encoding instead of the SSH wire encoding). The raw key bytes are stored
        // too - if they match, it is the same key: migrate the entry to the new
        // fingerprint silently instead of raising a false "key changed" alarm.
        if (key != null && entry.getEncodedKey() != null && !entry.getEncodedKey().isEmpty()
                && entry.getEncodedKey().equals(Base64.getEncoder().encodeToString(key.getEncoded()))) {
            addHost(host, port, type, fingerprint, key);
            return VerifyResult.KNOWN_OK;
        }

        return VerifyResult.KNOWN_CHANGED;
    }

    /**
     * Get the stored fingerprint for a host. Reports the entry of the key type
     * most recently checked with {@link #verify} for this host; if the host has
     * not been verified, the single stored entry, or all of them ("TYPE: fp")
     * when several types are known.
     */
    public synchronized String getStoredFingerprint(String host, int port) {
        String hostKey = makeHostKey(host, port);
        String type = lastVerifiedType.get(hostKey);
        if (type != null) {
            String fp = getStoredFingerprint(host, port, type);
            if (fp != null) {
                return fp;
            }
        }

        List<HostEntry> entries = entriesFor(hostKey);
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() == 1) {
            return entries.get(0).getFingerprint();
        }
        StringBuilder sb = new StringBuilder();
        for (HostEntry e : entries) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKeyType()).append(": ").append(e.getFingerprint());
        }
        return sb.toString();
    }

    /**
     * Get the stored fingerprint for a host and key type (falling back to a
     * legacy type-less entry), or {@code null} if none.
     */
    public synchronized String getStoredFingerprint(String host, int port, String keyType) {
        String hostKey = makeHostKey(host, port);
        HostEntry entry = knownHosts.get(entryKey(hostKey, keyType));
        if (entry == null) {
            entry = knownHosts.get(entryKey(hostKey, ANY_KEY_TYPE));
        }
        return entry != null ? entry.getFingerprint() : null;
    }

    /**
     * Add or update the known host entry for this key type. Other key types of
     * the same host are left untouched; a legacy type-less entry is superseded.
     */
    public synchronized void addHost(String host, int port, String keyType, String fingerprint, PublicKey key) {
        String hostKey = makeHostKey(host, port);
        String type = normalizeKeyType(keyType);
        if (ANY_KEY_TYPE.equals(type)) {
            type = keyTypeOf(key);
        }
        String encodedKey = key != null ? Base64.getEncoder().encodeToString(key.getEncoded()) : "";

        if (!ANY_KEY_TYPE.equals(type)) {
            knownHosts.remove(entryKey(hostKey, ANY_KEY_TYPE));
        }
        knownHosts.put(entryKey(hostKey, type), new HostEntry(hostKey, type, fingerprint, encodedKey));

        save();
    }

    /**
     * Remove all known host entries (every key type) for the host
     */
    public synchronized void removeHost(String host, int port) {
        String hostKey = makeHostKey(host, port);
        knownHosts.values().removeIf(e -> e.getHostKey().equals(hostKey));
        lastVerifiedType.remove(hostKey);
        save();
    }

    /**
     * Remove the known host entry of one key type
     */
    public synchronized void removeHost(String host, int port, String keyType) {
        String hostKey = makeHostKey(host, port);
        knownHosts.remove(entryKey(hostKey, keyType));
        save();
    }

    /**
     * Get all known hosts (one entry per host and key type)
     */
    public synchronized List<HostEntry> getAllHosts() {
        return new ArrayList<>(knownHosts.values());
    }

    private List<HostEntry> entriesFor(String hostKey) {
        List<HostEntry> result = new ArrayList<>();
        for (HostEntry e : knownHosts.values()) {
            if (e.getHostKey().equals(hostKey)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * A token that is a fingerprint rather than a key type name (e.g.
     * {@code SHA256:...}); used to recognise legacy lines with no key type.
     */
    private static boolean looksLikeFingerprint(String token) {
        return token.indexOf(':') >= 0;
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

                // Current format: hostkey keytype fingerprint [encodedkey]
                // Legacy format:  hostkey fingerprint [encodedkey]
                String[] parts = line.split("\\s+", 4);
                if (parts.length < 2) {
                    continue;
                }

                String hostKey = parts[0];
                String keyType;
                String fingerprint;
                String encodedKey;
                if (looksLikeFingerprint(parts[1])) {
                    String[] legacy = line.split("\\s+", 3);
                    keyType = ANY_KEY_TYPE;
                    fingerprint = legacy[1];
                    encodedKey = legacy.length > 2 ? legacy[2] : "";
                } else if (parts.length >= 3) {
                    keyType = parts[1];
                    fingerprint = parts[2];
                    encodedKey = parts.length > 3 ? parts[3] : "";
                } else {
                    continue;
                }

                HostEntry entry = new HostEntry(hostKey, keyType, fingerprint, encodedKey);
                knownHosts.put(entryKey(hostKey, entry.getKeyType()), entry);
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
            writer.write("# Format: hostkey keytype fingerprint encodedkey (one line per host and key type)");
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
