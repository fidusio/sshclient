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
        return props;
    }

    /**
     * Load from properties format
     */
    public static ConnectionConfig fromProperties(Properties props) {
        ConnectionConfig config = new ConnectionConfig();
        config.name = props.getProperty("name", "");
        config.host = props.getProperty("host", "");
        config.port = Integer.parseInt(props.getProperty("port", String.valueOf(JSSHConst.DEFAULT_SSH_PORT)));
        config.username = props.getProperty("username", "");
        config.useKeyAuth = Boolean.parseBoolean(props.getProperty("useKeyAuth", "false"));
        config.keyFile = props.getProperty("keyFile", "");
        config.terminalType = props.getProperty("terminalType", JSSHConst.DEFAULT_TERMINAL_TYPE);
        config.columns = Integer.parseInt(props.getProperty("columns", String.valueOf(JSSHConst.DEFAULT_TERMINAL_COLS)));
        config.rows = Integer.parseInt(props.getProperty("rows", String.valueOf(JSSHConst.DEFAULT_TERMINAL_ROWS)));
        config.x11Forwarding = Boolean.parseBoolean(props.getProperty("x11Forwarding", "false"));
        config.x11Display = props.getProperty("x11Display", JSSHConst.DEFAULT_X11_DISPLAY);
        return config;
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
