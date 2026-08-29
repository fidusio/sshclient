package io.xlogistx.jssh.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages saving and loading of connection configurations
 * Stores configs in ~/.jssh/connections/ directory
 */
public class ConnectionManager {

    private static ConnectionManager instance;

    private Path configPath;
    private Map<String, ConnectionConfig> connections = new LinkedHashMap<>();

    private ConnectionManager() {
        this(Paths.get(System.getProperty("user.home"), JSSHConst.CONFIG_DIR, JSSHConst.CONNECTIONS_DIR));
    }

    /**
     * Manager backed by an explicit directory. Package-private so tests can
     * point it at a temporary directory instead of {@code ~/.jssh/connections}.
     */
    ConnectionManager(Path configPath) {
        this.configPath = configPath;

        try {
            Files.createDirectories(configPath);
        } catch (IOException e) {
            System.err.println("Failed to create config directory: " + e.getMessage());
        }

        loadAll();
    }

    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    /**
     * Load all saved connections from disk
     */
    public void loadAll() {
        connections.clear();

        if (!Files.exists(configPath)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configPath, "*.properties")) {
            for (Path file : stream) {
                try {
                    // Numeric fields are parsed leniently (see ConnectionConfig.fromProperties),
                    // so only an unreadable file ends up here
                    ConnectionConfig config = load(file);
                    if (config != null && config.getName() != null) {
                        connections.put(config.getName(), config);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to load config " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read config directory: " + e.getMessage());
        }
    }

    /**
     * Load a single connection config from file
     */
    private ConnectionConfig load(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            props.load(reader);
        }
        return ConnectionConfig.fromProperties(props);
    }

    /**
     * Save a connection configuration
     */
    public void save(ConnectionConfig config) throws IOException {
        if (config.getName() == null || config.getName().trim().isEmpty()) {
            throw new IOException("Connection name cannot be empty");
        }

        String fileName = sanitizeFileName(config.getName()) + ".properties";
        Path file = configPath.resolve(fileName);

        Properties props = config.toProperties();
        try (Writer writer = Files.newBufferedWriter(file)) {
            props.store(writer, "JSSH Connection: " + config.getName());
        }

        connections.put(config.getName(), config);
    }

    /**
     * Delete a connection configuration
     */
    public void delete(String name) throws IOException {
        if (name == null) return;

        String fileName = sanitizeFileName(name) + ".properties";
        Path file = configPath.resolve(fileName);

        Files.deleteIfExists(file);
        connections.remove(name);
    }

    /**
     * Get a connection by name
     */
    public ConnectionConfig get(String name) {
        return connections.get(name);
    }

    /**
     * Get all connection names
     */
    public List<String> getConnectionNames() {
        return new ArrayList<>(connections.keySet());
    }

    /**
     * Get all connections
     */
    public List<ConnectionConfig> getAllConnections() {
        return new ArrayList<>(connections.values());
    }

    /**
     * Check if a connection exists
     */
    public boolean exists(String name) {
        return connections.containsKey(name);
    }

    /**
     * Get the config directory path
     */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * Sanitize name for use as filename. A hash of the full original name is
     * appended so that distinct names which sanitize to the same characters
     * (e.g. "a/b" and "a_b", or "user@host" and "user_host") never collide on
     * the same file.
     */
    private String sanitizeFileName(String name) {
        String base = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        String hash = Integer.toHexString(name.hashCode());
        return base + "-" + hash;
    }
}
