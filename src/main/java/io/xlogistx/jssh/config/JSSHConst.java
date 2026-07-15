package io.xlogistx.jssh.config;

import org.zoxweb.shared.app.AppVersionDAO;

import java.awt.Color;
import java.io.InputStream;
import java.util.Properties;

/**
 * Central constants class for the JSSH application.
 * Contains all constant values used across the project.
 */
public final class JSSHConst {

    // ============================================
    // APPLICATION INFO
    // ============================================

    /** Application name */
    public static final String APP_NAME = "JSSH - Java SSH Client";

    /** Application version - loaded from Maven-filtered properties file */
    public static final AppVersionDAO VERSION;

    // Static initializer to load version from properties file
    static {
        String version = "1.2.1"; // fallback default
        try (InputStream is = JSSHConst.class.getResourceAsStream("/jssh-version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                version = props.getProperty("jssh.version", version);
            }
        } catch (Exception e) {
            // Use fallback version if properties file not found
        }
        VERSION = new AppVersionDAO("JSSHClient::" + version);
    }

    // ============================================
    // SSH CONFIGURATION
    // ============================================

    /** Default SSH port */
    public static final int DEFAULT_SSH_PORT = 22;

    /** Connection timeout in milliseconds */
    public static final long CONNECTION_TIMEOUT_MS = 30000;

    /** Authentication timeout in milliseconds */
    public static final long AUTH_TIMEOUT_MS = 30000;

    /** Heartbeat interval in seconds */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 15;

    /** Shell channel open timeout in seconds */
    public static final int SHELL_OPEN_TIMEOUT_SECONDS = 30;

    /** Exec channel open timeout in seconds */
    public static final int EXEC_CHANNEL_TIMEOUT_SECONDS = 30;

    // ============================================
    // TERMINAL CONFIGURATION
    // ============================================

    /** Default terminal type */
    public static final String DEFAULT_TERMINAL_TYPE = "xterm-256color";

    /** Default terminal columns */
    public static final int DEFAULT_TERMINAL_COLS = 80;

    /** Default terminal rows */
    public static final int DEFAULT_TERMINAL_ROWS = 24;

    /** Minimum terminal columns */
    public static final int MIN_TERMINAL_COLS = 40;

    /** Maximum terminal columns */
    public static final int MAX_TERMINAL_COLS = 320;

    /** Minimum terminal rows */
    public static final int MIN_TERMINAL_ROWS = 10;

    /** Maximum terminal rows */
    public static final int MAX_TERMINAL_ROWS = 100;

    /** Minimum resize threshold columns */
    public static final int MIN_RESIZE_COLS = 10;

    /** Minimum resize threshold rows */
    public static final int MIN_RESIZE_ROWS = 5;

    /** Default character width in pixels */
    public static final int DEFAULT_CHAR_WIDTH = 8;

    /** Default character height in pixels */
    public static final int DEFAULT_CHAR_HEIGHT = 16;

    /** Maximum scrollback lines */
    public static final int MAX_SCROLLBACK_LINES = 10000;

    /** Supported terminal types */
    public static final String[] TERMINAL_TYPES = {
        "xterm-256color", "xterm", "vt100", "vt220", "linux", "ansi"
    };

    // ============================================
    // X11 FORWARDING
    // ============================================

    /** Default X11 display */
    public static final String DEFAULT_X11_DISPLAY = "localhost:0";

    /** X server port */
    public static final int X_SERVER_PORT = 6000;

    /** X11 socket connection timeout in milliseconds */
    public static final int X11_SOCKET_TIMEOUT_MS = 100;

    // ============================================
    // FILE PATHS & DIRECTORIES
    // ============================================

    /** SSH directory name */
    public static final String SSH_DIR = ".ssh";

    /** Config directory name */
    public static final String CONFIG_DIR = ".jssh";

    /** Connections subdirectory */
    public static final String CONNECTIONS_DIR = "connections";

    /** Known hosts file name */
    public static final String KNOWN_HOSTS_FILE = "known_hosts";

    /** Default Ed25519 key path (relative to home) */
    public static final String DEFAULT_KEY_ED25519 = ".ssh/id_ed25519";

    /** Default RSA key path (relative to home) */
    public static final String DEFAULT_KEY_RSA = ".ssh/id_rsa";

    /** Default generated key name */
    public static final String DEFAULT_KEY_NAME = "id_ed25519";

    // ============================================
    // CRYPTOGRAPHY
    // ============================================

    /** Fingerprint hash algorithm */
    public static final String FINGERPRINT_ALGORITHM = "SHA-256";

    /** Fingerprint prefix */
    public static final String FINGERPRINT_PREFIX = "SHA256:";

    /** RSA key bits for generation */
    public static final int RSA_KEY_BITS = 4096;

    // ============================================
    // UI DIMENSIONS
    // ============================================

    /** Main window width */
    public static final int MAIN_WINDOW_WIDTH = 900;

    /** Main window height */
    public static final int MAIN_WINDOW_HEIGHT = 600;

    /** Connect dialog minimum width */
    public static final int CONNECT_DIALOG_MIN_WIDTH = 500;

    /** Connect dialog minimum height */
    public static final int CONNECT_DIALOG_MIN_HEIGHT = 400;

    /** Key manager dialog width */
    public static final int KEY_MANAGER_WIDTH = 600;

    /** Key manager dialog height */
    public static final int KEY_MANAGER_HEIGHT = 400;

    /** Tunnel dialog width */
    public static final int TUNNEL_DIALOG_WIDTH = 500;

    /** Tunnel dialog height */
    public static final int TUNNEL_DIALOG_HEIGHT = 400;

    /** SFTP window width */
    public static final int SFTP_WINDOW_WIDTH = 800;

    /** SFTP window height */
    public static final int SFTP_WINDOW_HEIGHT = 600;

    // ============================================
    // PORT FORWARDING
    // ============================================

    /** Default local tunnel port */
    public static final int DEFAULT_LOCAL_TUNNEL_PORT = 8080;

    /** Default remote tunnel port */
    public static final int DEFAULT_REMOTE_TUNNEL_PORT = 8080;

    /** Default target port for tunnels */
    public static final int DEFAULT_TARGET_PORT = 80;

    /** Minimum port number */
    public static final int MIN_PORT = 1;

    /** Maximum port number */
    public static final int MAX_PORT = 65535;

    /** Default tunnel host */
    public static final String DEFAULT_TUNNEL_HOST = "localhost";

    // ============================================
    // BUFFER SIZES
    // ============================================

    /** Shell read buffer size */
    public static final int SHELL_READ_BUFFER_SIZE = 8192;

    /** SFTP transfer buffer size */
    public static final int SFTP_BUFFER_SIZE = 32768;

    // ============================================
    // TERMINAL COLORS (ANSI 16 colors)
    // ============================================

    /** ANSI color index: Black */
    public static final int ANSI_BLACK = 0;

    /** ANSI color index: Red */
    public static final int ANSI_RED = 1;

    /** ANSI color index: Green */
    public static final int ANSI_GREEN = 2;

    /** ANSI color index: Yellow/Brown */
    public static final int ANSI_YELLOW = 3;

    /** ANSI color index: Blue */
    public static final int ANSI_BLUE = 4;

    /** ANSI color index: Magenta */
    public static final int ANSI_MAGENTA = 5;

    /** ANSI color index: Cyan */
    public static final int ANSI_CYAN = 6;

    /** ANSI color index: White (default foreground) */
    public static final int ANSI_WHITE = 7;

    /** ANSI color index: Bright Black */
    public static final int ANSI_BRIGHT_BLACK = 8;

    /** ANSI color index: Bright Red */
    public static final int ANSI_BRIGHT_RED = 9;

    /** ANSI color index: Bright Green */
    public static final int ANSI_BRIGHT_GREEN = 10;

    /** ANSI color index: Bright Yellow */
    public static final int ANSI_BRIGHT_YELLOW = 11;

    /** ANSI color index: Bright Blue */
    public static final int ANSI_BRIGHT_BLUE = 12;

    /** ANSI color index: Bright Magenta */
    public static final int ANSI_BRIGHT_MAGENTA = 13;

    /** ANSI color index: Bright Cyan */
    public static final int ANSI_BRIGHT_CYAN = 14;

    /** ANSI color index: Bright White */
    public static final int ANSI_BRIGHT_WHITE = 15;

    /** Default foreground color index */
    public static final int DEFAULT_FG_COLOR = ANSI_WHITE;

    /** Default background color index */
    public static final int DEFAULT_BG_COLOR = ANSI_BLACK;

    /** ANSI color palette */
    public static final Color[] ANSI_COLORS = {
        new Color(0, 0, 0),        // 0 Black
        new Color(170, 0, 0),      // 1 Red
        new Color(0, 170, 0),      // 2 Green
        new Color(170, 85, 0),     // 3 Yellow/Brown
        new Color(0, 0, 170),      // 4 Blue
        new Color(170, 0, 170),    // 5 Magenta
        new Color(0, 170, 170),    // 6 Cyan
        new Color(170, 170, 170),  // 7 White
        new Color(85, 85, 85),     // 8 Bright Black
        new Color(255, 85, 85),    // 9 Bright Red
        new Color(85, 255, 85),    // 10 Bright Green
        new Color(255, 255, 85),   // 11 Bright Yellow
        new Color(85, 85, 255),    // 12 Bright Blue
        new Color(255, 85, 255),   // 13 Bright Magenta
        new Color(85, 255, 255),   // 14 Bright Cyan
        new Color(255, 255, 255),  // 15 Bright White
    };

    // ============================================
    // UI STRINGS
    // ============================================

    /** New connection placeholder */
    public static final String NEW_CONNECTION_LABEL = "<New Connection>";

    /** Default SFTP remote path */
    public static final String SFTP_DEFAULT_REMOTE_PATH = "/";

    // Private constructor to prevent instantiation
    private JSSHConst() {
        throw new AssertionError("JSSHConst is a utility class and cannot be instantiated");
    }
}
