package io.xlogistx.jssh.ssh;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;

/**
 * Manager for SSH known_hosts file
 */
public class KnownHosts {
    
    private Path knownHostsFile;
    private Map<String, String> hosts = new HashMap<>();
    
    public KnownHosts() {
        String homeDir = System.getProperty("user.home");
        knownHostsFile = Paths.get(homeDir, ".ssh", "known_hosts");
        load();
    }
    
    public KnownHosts(String path) {
        knownHostsFile = Paths.get(path);
        load();
    }
    
    private void load() {
        hosts.clear();
        
        if (!Files.exists(knownHostsFile)) {
            return;
        }
        
        try {
            List<String> lines = Files.readAllLines(knownHostsFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String hostEntry = parts[0];
                    String keyType = parts[1];
                    String keyData = parts[2];
                    
                    // Host entry might be multiple hosts separated by commas
                    for (String host : hostEntry.split(",")) {
                        hosts.put(host + ":" + keyType, keyData);
                    }
                }
            }
        } catch (IOException e) {
            // Ignore - file might not exist
        }
    }
    
    /**
     * Check if a host key is known and matches
     */
    public VerifyResult verify(String host, int port, String keyType, String keyData) {
        String key = formatHost(host, port) + ":" + keyType;
        
        String storedKey = hosts.get(key);
        if (storedKey == null) {
            // Also check without port for standard port 22
            if (port == 22) {
                storedKey = hosts.get(host + ":" + keyType);
            }
            if (storedKey == null) {
                return VerifyResult.UNKNOWN;
            }
        }
        
        if (storedKey.equals(keyData)) {
            return VerifyResult.MATCH;
        } else {
            return VerifyResult.CHANGED;
        }
    }
    
    /**
     * Add a host key to known_hosts
     */
    public void addHost(String host, int port, String keyType, String keyData) throws IOException {
        String hostEntry = formatHost(host, port);
        
        // Ensure .ssh directory exists
        Path sshDir = knownHostsFile.getParent();
        if (!Files.exists(sshDir)) {
            Files.createDirectories(sshDir);
        }
        
        // Append to file
        String line = hostEntry + " " + keyType + " " + keyData + "\n";
        Files.writeString(knownHostsFile, line, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.APPEND);
        
        // Update cache
        hosts.put(hostEntry + ":" + keyType, keyData);
    }
    
    /**
     * Remove a host from known_hosts
     */
    public void removeHost(String host, int port) throws IOException {
        if (!Files.exists(knownHostsFile)) return;
        
        String hostEntry = formatHost(host, port);
        
        List<String> lines = Files.readAllLines(knownHostsFile);
        List<String> newLines = new ArrayList<>();
        
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                newLines.add(line);
                continue;
            }
            
            String[] parts = line.split("\\s+");
            if (parts.length >= 1) {
                boolean remove = false;
                for (String h : parts[0].split(",")) {
                    if (h.equals(hostEntry) || h.equals(host)) {
                        remove = true;
                        break;
                    }
                }
                if (!remove) {
                    newLines.add(line);
                }
            }
        }
        
        Files.write(knownHostsFile, newLines);
        load(); // Reload
    }
    
    /**
     * Get all known hosts
     */
    public Set<String> getHosts() {
        Set<String> result = new HashSet<>();
        for (String key : hosts.keySet()) {
            int lastColon = key.lastIndexOf(':');
            if (lastColon > 0) {
                result.add(key.substring(0, lastColon));
            }
        }
        return result;
    }
    
    private String formatHost(String host, int port) {
        if (port == 22) {
            return host;
        }
        return "[" + host + "]:" + port;
    }
    
    /**
     * Calculate fingerprint from public key
     */
    public static String getFingerprint(PublicKey key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().encodeToString(digest)
                                     .replace("=", "");
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Encode public key to Base64 string (for known_hosts)
     */
    public static String encodeKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
    
    public enum VerifyResult {
        MATCH,      // Key matches stored key
        CHANGED,    // Key exists but doesn't match (potential attack!)
        UNKNOWN     // Host not in known_hosts
    }
}
