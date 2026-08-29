package io.xlogistx.jssh.config;

import java.io.*;
import java.util.Properties;

/**
 * Holds SSH connection configuration settings
 */
public class ConnectionConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;           // Profile name
    private String host;
    private int port = JSSHConst.DEFAULT_SSH_PORT;
    private String username;
    private boolean useKeyAuth = false;
    private String keyFile;
    private String terminalType = JSSHConst.DEFAULT_TERMINAL_TYPE;
    private int columns = JSSHConst.DEFAULT_TERMINAL_COLS;
    private int rows = JSSHConst.DEFAULT_TERMINAL_ROWS;
    private boolean x11Forwarding = false;
    private String x11Display = JSSHConst.DEFAULT_X11_DISPLAY;
    // Line terminator for pasted text: AUTO (from server banner), LF, CR or CRLF
    private String pasteLineEnding = JSSHConst.PASTE_LINE_ENDING_AUTO;

    public ConnectionConfig() {
    }

    public ConnectionConfig(String name) {
        this.name = name;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isUseKeyAuth() {
        return useKeyAuth;
    }

    public void setUseKeyAuth(boolean useKeyAuth) {
        this.useKeyAuth = useKeyAuth;
    }

    public String getKeyFile() {
        return keyFile;
    }

    public void setKeyFile(String keyFile) {
        this.keyFile = keyFile;
    }

    public String getTerminalType() {
        return terminalType;
    }

    public void setTerminalType(String terminalType) {
        this.terminalType = terminalType;
    }

    public int getColumns() {
        return columns;
    }

    public void setColumns(int columns) {
        this.columns = columns;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public boolean isX11Forwarding() {
        return x11Forwarding;
    }

    public void setX11Forwarding(boolean x11Forwarding) {
        this.x11Forwarding = x11Forwarding;
    }

    public String getX11Display() {
        return x11Display;
    }

    public void setX11Display(String x11Display) {
        this.x11Display = x11Display;
    }

    public String getPasteLineEnding() {
        return pasteLineEnding;
    }

    public void setPasteLineEnding(String pasteLineEnding) {
        this.pasteLineEnding = normalizePasteLineEnding(pasteLineEnding);
    }

    /** Map any input to one of {@link JSSHConst#PASTE_LINE_ENDINGS}; unknown → AUTO. */
    static String normalizePasteLineEnding(String value) {
        if (value != null) {
            String v = value.trim().toUpperCase(java.util.Locale.ROOT);
            for (String allowed : JSSHConst.PASTE_LINE_ENDINGS) {
                if (allowed.equals(v)) {
                    return allowed;
                }
            }
        }
        return JSSHConst.PASTE_LINE_ENDING_AUTO;
    }

    /**
     * Save to properties format
     */
    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("name", name != null ? name : "");
        props.setProperty("host", host != null ? host : "");
        props.setProperty("port", String.valueOf(port));
        props.setProperty("username", username != null ? username : "");
        props.setProperty("useKeyAuth", String.valueOf(useKeyAuth));
        props.setProperty("keyFile", keyFile != null ? keyFile : "");
        props.setProperty("terminalType", terminalType != null ? terminalType : JSSHConst.DEFAULT_TERMINAL_TYPE);
        props.setProperty("columns", String.valueOf(columns));
        props.setProperty("rows", String.valueOf(rows));
        props.setProperty("x11Forwarding", String.valueOf(x11Forwarding));
        props.setProperty("x11Display", x11Display != null ? x11Display : JSSHConst.DEFAULT_X11_DISPLAY);
        props.setProperty("pasteLineEnding", normalizePasteLineEnding(pasteLineEnding));
        return props;
    }

    /**
     * Load from properties format
     */
    public static ConnectionConfig fromProperties(Properties props) {
        ConnectionConfig config = new ConnectionConfig();
        config.name = props.getProperty("name", "");
        config.host = props.getProperty("host", "");
        config.port = parseIntOrDefault(props, "port", JSSHConst.DEFAULT_SSH_PORT, JSSHConst.MIN_PORT, JSSHConst.MAX_PORT);
        config.username = props.getProperty("username", "");
        config.useKeyAuth = Boolean.parseBoolean(props.getProperty("useKeyAuth", "false"));
        config.keyFile = props.getProperty("keyFile", "");
        config.terminalType = props.getProperty("terminalType", JSSHConst.DEFAULT_TERMINAL_TYPE);
        config.columns = parseIntOrDefault(props, "columns", JSSHConst.DEFAULT_TERMINAL_COLS,
                JSSHConst.MIN_TERMINAL_COLS, JSSHConst.MAX_TERMINAL_COLS);
        config.rows = parseIntOrDefault(props, "rows", JSSHConst.DEFAULT_TERMINAL_ROWS,
                JSSHConst.MIN_TERMINAL_ROWS, JSSHConst.MAX_TERMINAL_ROWS);
        config.x11Forwarding = Boolean.parseBoolean(props.getProperty("x11Forwarding", "false"));
        config.x11Display = props.getProperty("x11Display", JSSHConst.DEFAULT_X11_DISPLAY);
        config.pasteLineEnding = normalizePasteLineEnding(props.getProperty("pasteLineEnding"));
        return config;
    }

    /**
     * Read an integer property leniently: a missing, blank, non-numeric or
     * out-of-range value falls back to {@code defaultValue} (with a warning on
     * stderr) instead of throwing, so one bad field never discards a whole
     * saved profile.
     */
    static int parseIntOrDefault(Properties props, String key, int defaultValue, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                warn(props, key, raw, defaultValue, "out of range " + min + ".." + max);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            warn(props, key, raw, defaultValue, "not a number");
            return defaultValue;
        }
    }

    private static void warn(Properties props, String key, String raw, int defaultValue, String reason) {
        String name = props.getProperty("name", "");
        System.err.println("Warning: connection profile" + (name.isEmpty() ? "" : " '" + name + "'")
                + ": invalid " + key + "=" + raw + " (" + reason + "), using default " + defaultValue);
    }

    @Override
    public String toString() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (host != null && !host.isEmpty()) {
            return (username != null ? username + "@" : "") + host + ":" + port;
        }
        return "New Connection";
    }
}
