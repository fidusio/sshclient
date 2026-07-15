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

    // Track active tunnels for UI display
    private final List<TunnelInfo> tunnels = new ArrayList<>();

    public interface HostKeyVerifier {
        boolean verify(String host, int port, String keyType, String fingerprint, PublicKey key);
    }

    public interface ConnectionListener {
        void onConnected(String serverVersion);

        void onDisconnected(String reason);

        void onError(String message);
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

        // Honor the caller's timeout. Note this window also covers the host-key
        // verification dialog, so callers should pass a value that allows for it.
        if (!connectFuture.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("Connection timeout");
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

        return authenticate(timeoutMs);
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

        return authenticate(timeoutMs);
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
            // Where the local X server listens: host:(6000+display). A remote/unix
            // host maps to loopback since forwarding targets the local display.
            String host = (x11Host == null || x11Host.isEmpty()
                    || "unix".equalsIgnoreCase(x11Host) || "localhost".equalsIgnoreCase(x11Host))
                    ? "127.0.0.1" : x11Host;
            int port = JSSHConst.X_SERVER_PORT + displayNumber;
            java.net.InetSocketAddress xServer = new java.net.InetSocketAddress(host, port);

            byte[] fakeCookie = new byte[16];
            new java.security.SecureRandom().nextBytes(fakeCookie);
            byte[] realCookie = io.xlogistx.jssh.ssh.x11.XAuthority.findMagicCookie(displayNumber);

            io.xlogistx.jssh.ssh.x11.X11ChannelFactory factory =
                    new io.xlogistx.jssh.ssh.x11.X11ChannelFactory(
                            xServer, JSSHConst.X11_SOCKET_TIMEOUT_MS * 10, fakeCookie, realCookie);
            registerChannelFactory(factory);

            return fakeCookie;
        } catch (Exception e) {
            System.err.println("X11 forwarding setup failed: " + e.getMessage());
            return null;
        }
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
                // Check common X server ports
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress("localhost", JSSHConst.X_SERVER_PORT), JSSHConst.X11_SOCKET_TIMEOUT_MS);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }
        return true;
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
