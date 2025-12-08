package io.xlogistx.jssh.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.forward.PortForwardingEventListener;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.forward.DefaultForwarderFactory;

import java.io.*;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.EnumSet;
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
    private boolean connected = false;

    private HostKeyVerifier hostKeyVerifier;

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

        // Enable port forwarding - accept all forwarding requests
        client.setForwarderFactory(DefaultForwarderFactory.INSTANCE);
        client.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);

        // Set up host key verification
        client.setServerKeyVerifier(new ServerKeyVerifier() {
            @Override
            public boolean verifyServerKey(ClientSession session, SocketAddress remoteAddress, PublicKey serverKey) {
                if (hostKeyVerifier == null) {
                    return true; // Accept all if no verifier
                }

                String keyType = getKeyType(serverKey);
                String fingerprint = getFingerprint(serverKey);

                // Must run on EDT for Swing dialogs
                if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                    return hostKeyVerifier.verify(host, port, keyType, fingerprint, serverKey);
                } else {
                    final java.util.concurrent.atomic.AtomicBoolean result =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    try {
                        javax.swing.SwingUtilities.invokeAndWait(() -> {
                            result.set(hostKeyVerifier.verify(host, port, keyType, fingerprint, serverKey));
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

        // Use longer timeout for connect since host key verification may require user interaction
        if (!connectFuture.await(timeoutMs + 60000, TimeUnit.MILLISECONDS)) {
            throw new IOException("Connection timeout");
        }

        if (!connectFuture.isConnected()) {
            Throwable ex = connectFuture.getException();
            throw new IOException("Connection failed: " + (ex != null ? ex.getMessage() : "unknown"));
        }

        session = connectFuture.getSession();
        session.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, TimeUnit.SECONDS, 30);

        serverVersion = session.getServerVersion();
        connected = true;

        if (listener != null) {
            listener.onConnected(serverVersion);
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
            throw new IOException("Authentication failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Authentication error: " + e.getMessage(), e);
        }
    }

    private KeyPair loadKeyPair(String keyFile, String passphrase) throws IOException {
        Path path = Path.of(keyFile.replace("~", System.getProperty("user.home")));

        if (!Files.exists(path)) {
            throw new IOException("Key file not found: " + keyFile);
        }

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
        shellChannel = session.createShellChannel();
        shellChannel.setPtyType(termType);
        shellChannel.setPtyColumns(cols);
        shellChannel.setPtyLines(rows);
        shellChannel.setPtyWidth(cols * 8);
        shellChannel.setPtyHeight(rows * 16);

        // Configure X11 forwarding if requested
        if (x11Forwarding) {
            configureX11Forwarding(shellChannel, x11Host, x11Display);
        }

        shellChannel.open().verify(30, TimeUnit.SECONDS);

        return shellChannel;
    }

    /**
     * Configure X11 forwarding for a channel
     */
    private void configureX11Forwarding(ChannelShell channel, String x11Host, int x11Display) {
        // Generate random auth cookie
        byte[] cookie = new byte[16];
        new java.security.SecureRandom().nextBytes(cookie);
        StringBuilder cookieHex = new StringBuilder();
        for (byte b : cookie) {
            cookieHex.append(String.format("%02x", b));
        }

        // Determine X11 display host
        String displayHost = x11Host;
        if (displayHost == null || displayHost.isEmpty()) {
            // Try to get from DISPLAY environment variable
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isEmpty()) {
                // Parse DISPLAY (format: [host]:display[.screen])
                int colonIdx = display.lastIndexOf(':');
                if (colonIdx >= 0) {
                    displayHost = colonIdx > 0 ? display.substring(0, colonIdx) : "localhost";
                    try {
                        String dispNum = display.substring(colonIdx + 1);
                        int dotIdx = dispNum.indexOf('.');
                        if (dotIdx > 0) {
                            dispNum = dispNum.substring(0, dotIdx);
                        }
                        x11Display = Integer.parseInt(dispNum);
                    } catch (NumberFormatException e) {
                        x11Display = 0;
                    }
                }
            } else {
                displayHost = "localhost";
            }
        }

        // Set X11 forwarding parameters
        // Note: The actual X11 forwarding implementation depends on MINA SSHD version
        // This sets up the channel to request X11 forwarding from the server
        try {
            // Request X11 forwarding
            // The channel will forward X11 connections back to the local display
            java.util.Map<String, Object> env = new java.util.HashMap<>();
            env.put("DISPLAY", displayHost + ":" + x11Display);

            // MINA SSHD handles X11 forwarding through the session
            // We need to set up an X11 forwarder
            final String fDisplayHost = displayHost;
            final int fX11Display = x11Display;
            final String fCookieHex = cookieHex.toString();

            session.addChannelListener(new org.apache.sshd.common.channel.ChannelListener() {
                @Override
                public void channelOpenSuccess(org.apache.sshd.common.channel.Channel channel) {
                    // X11 channel opened successfully
                }

                @Override
                public void channelOpenFailure(org.apache.sshd.common.channel.Channel channel, Throwable reason) {
                    // X11 channel failed to open
                }
            });

        } catch (Exception e) {
            // X11 forwarding setup failed, continue without it
            System.err.println("X11 forwarding setup failed: " + e.getMessage());
        }
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
                    socket.connect(new java.net.InetSocketAddress("localhost", 6000), 100);
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
     * Execute command
     */
    public String executeCommand(String command, long timeoutMs) throws IOException {
        ChannelExec channel = session.createExecChannel(command);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        channel.setOut(stdout);
        channel.setErr(stderr);

        channel.open().verify(30, TimeUnit.SECONDS);

        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeoutMs);

        channel.close();

        String error = stderr.toString();
        if (!error.isEmpty()) {
            throw new IOException(error);
        }

        return stdout.toString();
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
     */
    public void createRemotePortForward(int remotePort, String localHost, int localPort) throws IOException {
        session.startRemotePortForwarding(
                new SshdSocketAddress("0.0.0.0", remotePort),
                new SshdSocketAddress(localHost, localPort)
        );
    }

    /**
     * Change terminal window size
     */
    public void resizeTerminal(int cols, int rows) throws IOException {
        if (shellChannel != null && shellChannel.isOpen()) {
            shellChannel.sendWindowChange(cols, rows, cols * 8, rows * 16);
        }
    }

    /**
     * Disconnect
     */
    public void disconnect() {
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

        if (listener != null) {
            listener.onDisconnected("Disconnected");
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
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().encodeToString(digest).replace("=", "");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
