package io.xlogistx.jssh.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages saving and loading of connection configurations
 * Stores configs in ~/.jssh/connections/ directory
 */
public class ConnectionManager {

    private static final String CONFIG_DIR = ".jssh";
    private static final String CONNECTIONS_DIR = "connections";
    private static ConnectionManager instance;

    private Path configPath;
    private Map<String, ConnectionConfig> connections = new LinkedHashMap<>();

    private ConnectionManager() {
        String home = System.getProperty("user.home");
        configPath = Paths.get(home, CONFIG_DIR, CONNECTIONS_DIR);

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
     * Rename a connection
     */
    public void rename(String oldName, String newName) throws IOException {
        ConnectionConfig config = connections.get(oldName);
        if (config == null) {
            throw new IOException("Connection not found: " + oldName);
        }

        // Delete old file
        delete(oldName);

        // Save with new name
        config.setName(newName);
        save(config);
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
     * Sanitize name for use as filename
     */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Import a connection from a file
     */
    public ConnectionConfig importConnection(Path file) throws IOException {
        ConnectionConfig config = load(file);
        if (config != null) {
            save(config);
        }
        return config;
    }

    /**
     * Export a connection to a file
     */
    public void exportConnection(String name, Path file) throws IOException {
        ConnectionConfig config = connections.get(name);
        if (config == null) {
            throw new IOException("Connection not found: " + name);
        }

        Properties props = config.toProperties();
        try (Writer writer = Files.newBufferedWriter(file)) {
            props.store(writer, "JSSH Connection: " + name);
        }
    }
}
