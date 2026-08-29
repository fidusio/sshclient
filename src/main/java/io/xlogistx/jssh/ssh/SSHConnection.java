package io.xlogistx.jssh.ssh;

import io.xlogistx.jssh.config.JSSHConst;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.forward.PortForwardingEventListener;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.forward.DefaultForwarderFactory;

import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;

import java.io.*;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages SSH connections using Apache MINA SSHD
 */
public class SSHConnection {

    private SshClient client;
    private ClientSession session;
    private ChannelShell shellChannel;

    private String host;
    private int port;
    private String username;
    private String serverVersion;
    private volatile boolean connected = false;
    // Ensures onDisconnected is delivered to the listener exactly once, no matter
    // how many of disconnect()/sessionDisconnect/sessionClosed fire
    private final java.util.concurrent.atomic.AtomicBoolean disconnectNotified =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private HostKeyVerifier hostKeyVerifier;

    // Host-key prompt bookkeeping so connect()'s timeout excludes the time the
    // user spends in the (modal) verification dialog: nanoTime at which the
    // current prompt started (0 when none is open) and the total spent in
    // prompts that have completed.
    private final java.util.concurrent.atomic.AtomicLong hostKeyPromptStart =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong hostKeyPromptTotal =
            new java.util.concurrent.atomic.AtomicLong(0);

    // X11: warn at most once per connection when no local cookie is available
    private final java.util.concurrent.atomic.AtomicBoolean x11CookieWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String x11Warning;

    // Track active tunnels for UI display
    private final List<TunnelInfo> tunnels = new ArrayList<>();

    public interface HostKeyVerifier {
        boolean verify(String host, int port, String keyType, String fingerprint, PublicKey key);
    }

    public interface ConnectionListener {
        void onConnected(String serverVersion);

        void onDisconnected(String reason);

        void onError(String message);

        /**
         * Non-fatal problem the user should know about (e.g. X11 forwarding is
         * enabled but no local X authorization cookie could be found). Defaults
         * to {@link #onError(String)} so existing listeners still see it.
         */
        default void onWarning(String message) {
            onError(message);
        }
    }

    private ConnectionListener listener;

    public SSHConnection() {
        client = SshClient.setUpDefaultClient();

        // Configure keep-alive at client level
        // Send heartbeat every 15 seconds to keep connection alive
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(JSSHConst.HEARTBEAT_INTERVAL_SECONDS));
        // No idle timeout - connection stays open until explicitly closed
        CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ZERO);
        // No overall timeout
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, Duration.ZERO);

        // Enable port forwarding - accept all forwarding requests
        client.setForwarderFactory(DefaultForwarderFactory.INSTANCE);
        client.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);

        // Set up host key verification
        client.setServerKeyVerifier(new ServerKeyVerifier() {
            @Override
            public boolean verifyServerKey(ClientSession session, SocketAddress remoteAddress, PublicKey serverKey) {
                // If no verifier is configured the key cannot be checked against known
                // hosts - warn the user and let them decide instead of trusting blindly
                final HostKeyVerifier verifier =
                        hostKeyVerifier != null ? hostKeyVerifier : SSHConnection.this::confirmUnverifiedHostKey;

                String keyType = getKeyType(serverKey);
                String fingerprint = getFingerprint(serverKey);

                // The verifier may block in a modal dialog for as long as the user
                // likes; connect() pauses its timeout while this is in progress.
                hostKeyPromptStart.set(System.nanoTime());
                try {
                    // Must run on EDT for Swing dialogs
                    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                        return verifier.verify(host, port, keyType, fingerprint, serverKey);
                    } else {
                        final java.util.concurrent.atomic.AtomicBoolean result =
                                new java.util.concurrent.atomic.AtomicBoolean(false);
                        try {
                            javax.swing.SwingUtilities.invokeAndWait(() -> {
                                result.set(verifier.verify(host, port, keyType, fingerprint, serverKey));
                            });
                        } catch (Exception e) {
                            return false;
                        }
                        return result.get();
                    }
                } finally {
                    long started = hostKeyPromptStart.getAndSet(0);
                    if (started != 0) {
                        hostKeyPromptTotal.addAndGet(System.nanoTime() - started);
                    }
                }
            }
        });

        client.start();
    }

    public void setHostKeyVerifier(HostKeyVerifier verifier) {
        this.hostKeyVerifier = verifier;
    }

    /**
     * Fallback used when no HostKeyVerifier was configured: the key cannot be
     * checked against known hosts, so warn the user and ask before accepting.
     */
    private boolean confirmUnverifiedHostKey(String host, int port, String keyType, String fingerprint, PublicKey key) {
        int result = javax.swing.JOptionPane.showConfirmDialog(null,
                "WARNING: The host key cannot be verified\n" +
                        "(no host key verifier is configured).\n\n" +
                        "Host: " + host + (port != JSSHConst.DEFAULT_SSH_PORT ? ":" + port : "") + "\n" +
                        "Key type: " + keyType + "\n" +
                        "Fingerprint: " + fingerprint + "\n\n" +
                        "If this is not the server you expect, someone could be\n" +
                        "intercepting the connection.\n\n" +
                        "Accept this key and continue?",
                "Unverified Host Key",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        return result == javax.swing.JOptionPane.YES_OPTION;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    /**
     * Connect to SSH server
     */
    public void connect(String host, int port, long timeoutMs) throws IOException {
        this.host = host;
        this.port = port;

        ConnectFuture connectFuture = client.connect(null, host, port);

        // Honor the caller's timeout, but don't count time the user spends in the
        // host-key verification dialog (which runs inside this window, on MINA's
        // thread, via the ServerKeyVerifier above): poll the future and compare
        // the elapsed time minus prompt time against the deadline.
        final long startNanos = System.nanoTime();
        final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        final long pollMs = Math.max(1, Math.min(200, timeoutMs));
        while (!connectFuture.await(pollMs, TimeUnit.MILLISECONDS)) {
            long elapsed = System.nanoTime() - startNanos - hostKeyPromptNanos();
            if (elapsed >= timeoutNanos) {
                // Cancel so a late TCP connect doesn't leak a session: MINA closes
                // a session that completes after the future was cancelled.
                org.apache.sshd.common.future.CancelFuture cancellation = connectFuture.cancel();
                if (cancellation != null || !connectFuture.isDone()) {
                    throw new IOException("Connection timeout");
                }
                // Completed between the await and the cancel - fall through and use the result
                break;
            }
        }

        if (!connectFuture.isConnected()) {
            Throwable ex = connectFuture.getException();
            throw new IOException("Connection failed: " + (ex != null ? ex.getMessage() : "unknown"));
        }

        session = connectFuture.getSession();

        // Configure session-level keep-alive settings
        // Use IGNORE heartbeat type - sends SSH_MSG_IGNORE packets every 15 seconds
        session.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, TimeUnit.SECONDS, JSSHConst.HEARTBEAT_INTERVAL_SECONDS);

        // Set session-level timeouts to prevent disconnection
        CoreModuleProperties.IDLE_TIMEOUT.set(session, Duration.ZERO);  // No idle timeout
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(session, Duration.ZERO);  // No read timeout

        // Add session listener to detect disconnections
        session.addSessionListener(new SessionListener() {
            @Override
            public void sessionDisconnect(Session session, int reason, String msg, String language, boolean initiator) {
                connected = false;
                String disconnectReason = initiator ? "Disconnected by client" :
                        "Disconnected by server: " + (msg != null ? msg : "reason code " + reason);
                notifyDisconnected(disconnectReason);
            }

            @Override
            public void sessionClosed(Session session) {
                connected = false;
                notifyDisconnected("Session closed");
            }

            @Override
            public void sessionException(Session session, Throwable t) {
                if (listener != null) {
                    listener.onError("Session error: " + t.getMessage());
                }
            }
        });

        serverVersion = session.getServerVersion();
        connected = true;

        if (listener != null) {
            listener.onConnected(serverVersion);
        }
    }

    /**
     * Nanoseconds spent so far in host-key prompts (completed ones plus the one
     * currently open, if any).
     */
    private long hostKeyPromptNanos() {
        long total = hostKeyPromptTotal.get();
        long started = hostKeyPromptStart.get();
        if (started != 0) {
            total += System.nanoTime() - started;
        }
        return total;
    }

    /**
     * Deliver onDisconnected to the listener at most once for this connection.
     */
    private void notifyDisconnected(String reason) {
        if (disconnectNotified.compareAndSet(false, true) && listener != null) {
            listener.onDisconnected(reason);
        }
    }

    /**
     * Authenticate with password
     */
    public boolean authenticatePassword(String username, String password, long timeoutMs) throws IOException {
        this.username = username;
        session.setUsername(username);
        session.addPasswordIdentity(password);

        try {
            return authenticate(timeoutMs);
        } finally {
            // MINA keeps registered identities for the session's lifetime; drop
            // the plaintext as soon as the auth exchange is over (success or not)
            session.removePasswordIdentity(password);
        }
    }

    /**
     * Authenticate with password held in a char[] so the caller can wipe it;
     * the String required by the MINA API only exists transiently here
     */
    public boolean authenticatePassword(String username, char[] password, long timeoutMs) throws IOException {
        return authenticatePassword(username, password != null ? new String(password) : null, timeoutMs);
    }

    /**
     * Authenticate with public key, passphrase held in a char[] so the caller can
     * wipe it; an empty or null array means the key is not encrypted
     */
    public boolean authenticatePublicKey(String username, String keyFile, char[] passphrase, long timeoutMs)
            throws IOException {
        return authenticatePublicKey(username, keyFile,
                (passphrase == null || passphrase.length == 0) ? null : new String(passphrase), timeoutMs);
    }

    /**
     * Authenticate with public key
     */
    public boolean authenticatePublicKey(String username, String keyFile, String passphrase, long timeoutMs)
            throws IOException {
        this.username = username;
        session.setUsername(username);

        KeyPair keyPair = loadKeyPair(keyFile, passphrase);
        session.addPublicKeyIdentity(keyPair);

        try {
            return authenticate(timeoutMs);
        } finally {
            // Don't leave the private key registered on the session after auth
            session.removePublicKeyIdentity(keyPair);
        }
    }

    private boolean authenticate(long timeoutMs) throws IOException {
        try {
            session.auth().verify(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (org.apache.sshd.common.SshException e) {
            e.printStackTrace();
            throw new IOException("Authentication failed: " + e.getMessage(), e);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException("Authentication error: " + e.getMessage(), e);
        }
    }

    private KeyPair loadKeyPair(String keyFile, String passphrase) throws IOException {
        Path path = Paths.get(expandHome(keyFile));

        if (!Files.exists(path)) {
            throw new IOException("Key file not found: " + keyFile);
        }

        return loadKeyPairFromPath(path, passphrase);
    }

    /**
     * Expand a leading "~/" (or a bare "~") to the user's home directory.
     * Only the leading element is expanded, so paths that legitimately contain
     * '~' elsewhere (e.g. "backup~1") are left untouched.
     */
    private static String expandHome(String path) {
        if (path == null) {
            return null;
        }
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    private KeyPair loadKeyPairFromPath(Path path, String passphrase) throws IOException {

        try {
            org.apache.sshd.common.config.keys.FilePasswordProvider passwordProvider =
                    passphrase != null && !passphrase.isEmpty() ?
                            org.apache.sshd.common.config.keys.FilePasswordProvider.of(passphrase) :
                            org.apache.sshd.common.config.keys.FilePasswordProvider.EMPTY;

            // Use KeyPairResourceLoader to load keys
            org.apache.sshd.common.config.keys.loader.KeyPairResourceLoader loader =
                    org.apache.sshd.common.util.security.SecurityUtils.getKeyPairResourceParser();

            try (InputStream is = Files.newInputStream(path)) {
                Iterable<KeyPair> keyPairs = loader.loadKeyPairs(null,
                        org.apache.sshd.common.NamedResource.ofName(path.toString()),
                        passwordProvider, is);

                for (KeyPair kp : keyPairs) {
                    return kp;
                }
            }
            throw new IOException("No keys found in file");
        } catch (java.security.GeneralSecurityException e) {
            e.printStackTrace();
            throw new IOException("Failed to load key: " + e.getMessage(), e);
        }
    }

    /**
     * Open interactive shell
     */
    public ChannelShell openShell(String termType, int cols, int rows) throws IOException {
        return openShell(termType, cols, rows, false, null, 0);
    }

    /**
     * Open interactive shell with optional X11 forwarding
     * @param termType terminal type (e.g., "xterm-256color")
     * @param cols number of columns
     * @param rows number of rows
     * @param x11Forwarding enable X11 forwarding
     * @param x11Host X11 display host (e.g., "localhost", null for default)
     * @param x11Display X11 display number (e.g., 0 for :0)
     */
    public ChannelShell openShell(String termType, int cols, int rows,
                                  boolean x11Forwarding, String x11Host, int x11Display) throws IOException {
        // Prepare X11 forwarding (register the local acceptor) before opening.
        String x11CookieHex = null;
        int x11Screen = 0;
        if (x11Forwarding) {
            byte[] fakeCookie = setupX11Forwarding(x11Host, x11Display);
            if (fakeCookie != null) {
                x11CookieHex = toHex(fakeCookie);
            }
        }

        if (x11CookieHex != null) {
            // Use a shell channel that sends x11-req *before* the shell request,
            // so the server sets $DISPLAY in the shell's environment
            io.xlogistx.jssh.ssh.x11.X11ChannelShell x11Shell =
                    new io.xlogistx.jssh.ssh.x11.X11ChannelShell(null, java.util.Collections.emptyMap(),
                            x11CookieHex, x11Screen);
            session.getService(org.apache.sshd.common.session.ConnectionService.class)
                    .registerChannel(x11Shell);
            shellChannel = x11Shell;
        } else {
            shellChannel = session.createShellChannel();
        }

        shellChannel.setPtyType(termType);
        shellChannel.setPtyColumns(cols);
        shellChannel.setPtyLines(rows);
        shellChannel.setPtyWidth(cols * JSSHConst.DEFAULT_CHAR_WIDTH);
        shellChannel.setPtyHeight(rows * JSSHConst.DEFAULT_CHAR_HEIGHT);

        // Set UTF-8 locale environment variables for proper Unicode/box-drawing character support
//        shellChannel.setEnv("LANG", "en_US.UTF-8");
//        shellChannel.setEnv("LC_ALL", "en_US.UTF-8");

        shellChannel.open().verify(JSSHConst.SHELL_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return shellChannel;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Register the client-side X11 acceptor and compute the auth cookie.
     * Returns the fake cookie advertised to the server (16 random bytes), or
     * {@code null} if forwarding could not be prepared.
     *
     * <p>Flow: the client tells the server (via {@code x11-req}) to forward X11
     * connections. When a GUI program on the server connects to its forwarded
     * display, the server opens an "x11" channel back to us, which
     * {@link io.xlogistx.jssh.ssh.x11.X11ChannelFactory} accepts and bridges to
     * the local X server.
     */
    private byte[] setupX11Forwarding(String x11Host, int displayNumber) {
        try {
            boolean local = isLocalX11Host(x11Host);

            // For a local display, connect to the X server's Unix-domain socket
            // (like `ssh -X`); TCP is the fallback and the only option for a
            // remote display host or on systems without the socket (e.g. Windows).
            String unixSocketPath = x11SocketPathFor(x11Host, displayNumber);
            String tcpHost = local ? "127.0.0.1" : x11Host;
            int port = JSSHConst.X_SERVER_PORT + displayNumber;
            java.net.InetSocketAddress xServer = new java.net.InetSocketAddress(tcpHost, port);

            byte[] fakeCookie = new byte[16];
            new java.security.SecureRandom().nextBytes(fakeCookie);
            byte[] realCookie = io.xlogistx.jssh.ssh.x11.XAuthority.findMagicCookie(displayNumber);
            if (realCookie == null) {
                // Without the local cookie the fake one we advertise to the server
                // is passed through unchanged, and an X server with access control
                // enabled will refuse every forwarded connection - say so, once.
                String xauth = System.getenv("XAUTHORITY");
                String where = (xauth != null && !xauth.isEmpty())
                        ? "$XAUTHORITY (" + xauth + ")"
                        : System.getProperty("user.home") + File.separator + ".Xauthority ($XAUTHORITY is not set)";
                warnX11("X11 forwarding: no MIT-MAGIC-COOKIE-1 for display :" + displayNumber
                        + " found in " + where + ". Unless the local X server runs with access control"
                        + " disabled (e.g. VcXsrv/Xming '-ac', or 'xhost +localhost'), forwarded X11"
                        + " connections will be rejected and remote GUI programs will report"
                        + " 'Cannot open display' / 'Authorization required'. Check 'xauth list' or"
                        + " set XAUTHORITY before starting JSSH.");
            }

            io.xlogistx.jssh.ssh.x11.X11ChannelFactory factory =
                    new io.xlogistx.jssh.ssh.x11.X11ChannelFactory(
                            unixSocketPath, xServer, JSSHConst.X11_SOCKET_TIMEOUT_MS * 10, fakeCookie, realCookie);
            registerChannelFactory(factory);

            return fakeCookie;
        } catch (Exception e) {
            System.err.println("X11 forwarding setup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Report an X11 forwarding problem once per connection: to the listener's
     * {@link ConnectionListener#onWarning(String)} (if any) and to stderr. The
     * text is also kept for {@link #getX11Warning()}.
     */
    private void warnX11(String message) {
        if (!x11CookieWarned.compareAndSet(false, true)) {
            return;
        }
        x11Warning = message;
        System.err.println("[JSSH] " + message);
        ConnectionListener l = listener;
        if (l != null) {
            l.onWarning(message);
        }
    }

    /**
     * The X11 forwarding warning raised for this connection (e.g. no local
     * authorization cookie), or {@code null} if none.
     */
    public String getX11Warning() {
        return x11Warning;
    }

    /**
     * A display host that refers to this machine. Besides the usual names, a
     * host starting with '/' is the socket-path form macOS uses in $DISPLAY
     * ({@code /private/tmp/com.apple.launchd.XXXX/org.xquartz:0}, split by the
     * connect dialog at the last ':').
     */
    static boolean isLocalX11Host(String x11Host) {
        return x11Host == null || x11Host.isEmpty()
                || x11Host.startsWith("/")
                || "unix".equalsIgnoreCase(x11Host) || "localhost".equalsIgnoreCase(x11Host)
                || "127.0.0.1".equals(x11Host);
    }

    /**
     * Unix-domain socket to reach the local X server, or {@code null} for a
     * remote display host (TCP only). A path-style host is the launchd socket
     * directory prefix; the socket file itself is named {@code <prefix>:<n>}.
     */
    static String x11SocketPathFor(String x11Host, int displayNumber) {
        if (x11Host != null && x11Host.startsWith("/")) {
            return x11Host + ":" + displayNumber;
        }
        return isLocalX11Host(x11Host) ? resolveLocalX11Socket(displayNumber) : null;
    }

    /**
     * Resolve the local X server's Unix-domain socket path. Uses the launchd
     * path from $DISPLAY on macOS (a path-style display) and the standard
     * {@code /tmp/.X11-unix/X<n>} on Linux. Returns a path that may not exist;
     * the caller falls back to TCP when it doesn't.
     */
    private static String resolveLocalX11Socket(int displayNumber) {
        String display = System.getenv("DISPLAY");
        if (display != null && display.startsWith("/")) {
            // macOS/launchd: DISPLAY is the socket path plus a :display[.screen] suffix
            int colon = display.lastIndexOf(':');
            int dot = colon >= 0 ? display.indexOf('.', colon) : -1;
            return dot > colon ? display.substring(0, dot) : display;
        }
        return "/tmp/.X11-unix/X" + displayNumber;
    }

    /**
     * Add a channel factory to the client, replacing any existing one of the
     * same name (so incoming channels of that type are accepted).
     */
    private void registerChannelFactory(org.apache.sshd.common.channel.ChannelFactory factory) {
        java.util.List<org.apache.sshd.common.channel.ChannelFactory> factories =
                new java.util.ArrayList<>(client.getChannelFactories());
        factories.removeIf(f -> factory.getName().equals(f.getName()));
        factories.add(factory);
        client.setChannelFactories(factories);
    }


    /**
     * Check if X11 forwarding is available on this system
     */
    public static boolean isX11Available() {
        String display = System.getenv("DISPLAY");
        if (display == null || display.isEmpty()) {
            // On Windows, check for X server (like VcXsrv, Xming)
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                return isX11DisplayReachable(":0");
            }
            return false;
        }
        // $DISPLAY being set only says an X server *was* there when the shell
        // started; check that its socket (or TCP port) is actually reachable.
        return isX11DisplayReachable(display);
    }

    /**
     * Split a display string ({@code [host]:n[.screen]}, or the macOS launchd
     * path form) into {host, displayNumber}. The host is {@code ""} for {@code :n}.
     */
    static String[] splitX11Display(String display) {
        String host = display;
        int num = 0;
        int colon = display.lastIndexOf(':');
        if (colon >= 0) {
            host = display.substring(0, colon);
            String n = display.substring(colon + 1);
            int dot = n.indexOf('.');
            if (dot > 0) {
                n = n.substring(0, dot);
            }
            try {
                num = Integer.parseInt(n.trim());
            } catch (NumberFormatException e) {
                num = 0;
            }
        }
        return new String[] { host, Integer.toString(num) };
    }

    /**
     * True if the X server for {@code display} can be reached: its Unix-domain
     * socket exists (local display) or its TCP port accepts a connection.
     */
    static boolean isX11DisplayReachable(String display) {
        String[] parts = splitX11Display(display);
        String host = parts[0];
        int num = Integer.parseInt(parts[1]);
        String socketPath = x11SocketPathFor(host, num);
        if (socketPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(socketPath))) {
            return true;
        }
        String tcpHost = isLocalX11Host(host) ? "127.0.0.1" : host;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(tcpHost, JSSHConst.X_SERVER_PORT + num),
                    JSSHConst.X11_SOCKET_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Open SFTP client
     */
    public SftpClient openSftp() throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }

    /**
     * Create local port forward
     */
    public void createLocalPortForward(int localPort, String remoteHost, int remotePort) throws IOException {
        session.startLocalPortForwarding(
                new SshdSocketAddress("127.0.0.1", localPort),
                new SshdSocketAddress(remoteHost, remotePort)
        );
    }

    /**
     * Create remote port forward
     * @param allowExternal bind the server-side port to all interfaces (0.0.0.0) so other
     *                      machines can connect (requires GatewayPorts on the server);
     *                      otherwise bind loopback so only the server itself can connect
     */
    public void createRemotePortForward(int remotePort, String localHost, int localPort, boolean allowExternal) throws IOException {
        session.startRemotePortForwarding(
                new SshdSocketAddress(allowExternal ? "0.0.0.0" : "127.0.0.1", remotePort),
                new SshdSocketAddress(localHost, localPort)
        );
    }

    /**
     * Change terminal window size
     */
    public void resizeTerminal(int cols, int rows) throws IOException {
        if (shellChannel != null && shellChannel.isOpen()) {
            shellChannel.sendWindowChange(cols, rows, cols * JSSHConst.DEFAULT_CHAR_WIDTH, rows * JSSHConst.DEFAULT_CHAR_HEIGHT);
        }
    }

    /**
     * Disconnect
     */
    public void disconnect() {
        // Only announce a disconnect if we were actually connected; a failed
        // connect attempt that calls close() should stay silent
        boolean wasConnected = connected;
        connected = false;

        try {
            if (shellChannel != null) {
                shellChannel.close();
            }
        } catch (IOException e) {
        }

        try {
            if (session != null) {
                session.close();
            }
        } catch (IOException e) {
        }

        if (wasConnected) {
            notifyDisconnected("Disconnected");
        }
    }

    /**
     * Close client
     */
    public void close() {
        disconnect();
        if (client != null) {
            client.stop();
        }
    }

    // Getters
    public boolean isConnected() {
        return connected && session != null && session.isOpen();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    /**
     * Line terminator the remote host expects for pasted text, derived from the
     * server's SSH version banner (e.g. {@code SSH-2.0-OpenSSH_for_Windows_9.5}).
     */
    public io.xlogistx.jssh.terminal.TerminalPanel.LineEnding getHostLineEnding() {
        return detectHostLineEnding(serverVersion);
    }

    /**
     * Windows OpenSSH (conpty) treats CR as Enter and a following LF as a second
     * keystroke; Unix ttys and editors take LF. Everything not identifiable as
     * Windows is treated as Unix-like.
     */
    public static io.xlogistx.jssh.terminal.TerminalPanel.LineEnding detectHostLineEnding(String serverVersion) {
        if (serverVersion != null && serverVersion.toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            return io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.CR;
        }
        return io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.LF;
    }

    /**
     * Line terminator for pasted text: the profile's explicit choice
     * ({@code LF}, {@code CR}, {@code CRLF}) or, for {@code AUTO}/blank/unknown,
     * the convention detected from the server banner.
     */
    public io.xlogistx.jssh.terminal.TerminalPanel.LineEnding resolvePasteLineEnding(String override) {
        return resolvePasteLineEnding(override, serverVersion);
    }

    public static io.xlogistx.jssh.terminal.TerminalPanel.LineEnding resolvePasteLineEnding(
            String override, String serverVersion) {
        if (override != null) {
            String o = override.trim().toUpperCase(java.util.Locale.ROOT);
            if (!o.isEmpty() && !JSSHConst.PASTE_LINE_ENDING_AUTO.equals(o)) {
                try {
                    return io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.valueOf(o);
                } catch (IllegalArgumentException ignore) {
                    // fall through to auto-detection
                }
            }
        }
        return detectHostLineEnding(serverVersion);
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public ClientSession getSession() {
        return session;
    }

    public ChannelShell getShellChannel() {
        return shellChannel;
    }

    // Utility methods
    private String getKeyType(PublicKey key) {
        String alg = key.getAlgorithm();
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
                return alg;
        }
    }

    private String getFingerprint(PublicKey key) {
        try {
            // SHA-256 over the SSH wire encoding of the key (not the X.509 encoding),
            // so the result matches `ssh-keygen -lf` and can be verified out-of-band
            return org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // Tunnel management methods
    public void addTunnel(TunnelInfo tunnel) {
        tunnels.add(tunnel);
    }

    /**
     * Stop and remove a specific tunnel. Removing by identity (rather than by
     * table row index) keeps things consistent when more than one tunnel dialog
     * is open on the same connection.
     */
    public void removeTunnel(TunnelInfo tunnel) throws IOException {
        if (tunnel == null || !tunnels.contains(tunnel)) {
            return; // Already removed (e.g. by another dialog) - nothing to do
        }

        // Actually stop the port forwarding in the SSH session
        if ("Local".equals(tunnel.getType())) {
            session.stopLocalPortForwarding(
                new SshdSocketAddress("127.0.0.1", tunnel.getLocalPort())
            );
        } else {
            session.stopRemotePortForwarding(
                new SshdSocketAddress(tunnel.getBindAddress(), tunnel.getRemotePort())
            );
        }

        tunnels.remove(tunnel);
    }

    public List<TunnelInfo> getTunnels() {
        return Collections.unmodifiableList(tunnels);
    }

    /**
     * Stores information about an active port forwarding tunnel
     */
    public static class TunnelInfo {
        private final String type;
        private final int localPort;
        private final String remoteHost;
        private final int remotePort;
        private final String bindAddress;

        public TunnelInfo(String type, int localPort, String remoteHost, int remotePort) {
            this(type, localPort, remoteHost, remotePort, "127.0.0.1");
        }

        public TunnelInfo(String type, int localPort, String remoteHost, int remotePort, String bindAddress) {
            this.type = type;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.bindAddress = bindAddress;
        }

        public String getType() { return type; }
        public int getLocalPort() { return localPort; }
        public String getRemoteHost() { return remoteHost; }
        public int getRemotePort() { return remotePort; }
        public String getBindAddress() { return bindAddress; }

        public boolean isExternal() { return "0.0.0.0".equals(bindAddress); }
    }
}
