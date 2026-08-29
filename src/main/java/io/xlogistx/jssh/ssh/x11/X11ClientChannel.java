package io.xlogistx.jssh.ssh.x11;

import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Client side of X11 forwarding: a channel opened by the SSH server (channel
 * type "x11") whenever a graphical program on the server connects to the
 * forwarded display. This channel connects out to the user's real local X
 * server and pipes bytes in both directions.
 *
 * <p>Connects to the local X server the same way {@code ssh -X} does: via the
 * Unix-domain socket ({@code /tmp/.X11-unix/X<n>} on Linux, or the launchd path
 * from {@code $DISPLAY} on macOS) when the display is local, falling back to TCP
 * ({@code 127.0.0.1:6000+n}) for a remote display or when no Unix socket exists
 * (e.g. Windows/VcXsrv). Unix-domain socket support requires Java 16+.
 *
 * <p>MINA SSHD ships only the display-proxy direction (see
 * {@code org.apache.sshd.server.x11.DefaultX11ForwardSupport}); this class
 * provides the missing client acceptor direction, modeled on MINA's own
 * {@code TcpipServerChannel} / {@code ChannelForwardedX11}.
 */
public class X11ClientChannel extends AbstractServerChannel {

    private final String unixSocketPath;    // preferred local transport (may be null)
    private final InetSocketAddress xServerAddress;   // TCP fallback / remote display
    private final int connectTimeoutMs;
    private final byte[] fakeCookie;   // what we advertised to the server (may be null)
    private final byte[] realCookie;   // local X server's cookie (may be null)

    private Closeable connection;
    private InputStream fromXServer;
    private OutputStream toXServer;
    private ChannelOutputStream toPeer;

    // First-packet cookie replacement state
    private boolean authRewritten;
    private byte[] pending = new byte[0];

    public X11ClientChannel(String unixSocketPath, InetSocketAddress xServerAddress, int connectTimeoutMs,
                            byte[] fakeCookie, byte[] realCookie) {
        super("x11", Collections.emptyList(), null);
        this.unixSocketPath = unixSocketPath;
        this.xServerAddress = xServerAddress;
        this.connectTimeoutMs = connectTimeoutMs;
        this.fakeCookie = fakeCookie;
        this.realCookie = realCookie;
        // Nothing to rewrite unless we have both cookies of equal length
        this.authRewritten = !canRewriteCookie();
    }

    private boolean canRewriteCookie() {
        return fakeCookie != null && realCookie != null && fakeCookie.length == realCookie.length
                && fakeCookie.length > 0;
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        // x11 channel-open payload is (originator address, originator port) - not needed here
        DefaultOpenFuture f = new DefaultOpenFuture(this, futureLock);
        try {
            String transport = connectToXServer();
            toPeer = new ChannelOutputStream(this, getRemoteWindow(), log, SshConstants.SSH_MSG_CHANNEL_DATA, true);

            // Ensure the local X server connection is closed whenever the SSH channel closes
            addCloseFutureListener(future -> closeConnection());

            Thread pump = new Thread(this::pumpXServerToPeer, "x11-forward-" + getChannelId());
            pump.setDaemon(true);
            pump.start();

            if (log.isDebugEnabled()) {
                log.debug("[X11] connected to local X server via {} (cookieRewrite={})",
                        transport, canRewriteCookie() ? "yes" : "passthrough");
            }
            // Required: tells MINA to send SSH_MSG_CHANNEL_OPEN_CONFIRMATION so the
            // server starts forwarding X11 data to us. Without this the channel
            // stays half-open and no data ever arrives.
            signalChannelOpenSuccess();
            f.setOpened();
        } catch (IOException e) {
            log.warn("[X11] failed to connect to local X server (unix={}, tcp={}): {}",
                    unixSocketPath, xServerAddress, e.getMessage());
            f.setException(e);
            close(true);
        }
        return f;
    }

    /**
     * Connect to the local X server, preferring the Unix-domain socket (like
     * {@code ssh -X}) and falling back to TCP. Returns a short label of the
     * transport used, for logging.
     */
    private String connectToXServer() throws IOException {
        if (unixSocketPath != null) {
            Path path = Paths.get(unixSocketPath);
            if (Files.exists(path)) {
                try {
                    SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(path));
                    connection = ch;
                    fromXServer = Channels.newInputStream(ch);
                    toXServer = Channels.newOutputStream(ch);
                    return "unix:" + unixSocketPath;
                } catch (IOException e) {
                    // Fall back to TCP below
                    if (log.isDebugEnabled()) {
                        log.debug("[X11] unix socket {} failed ({}); falling back to TCP", unixSocketPath, e.getMessage());
                    }
                }
            }
        }

        Socket socket = new Socket();
        socket.connect(xServerAddress, connectTimeoutMs);
        socket.setTcpNoDelay(true);
        connection = socket;
        fromXServer = socket.getInputStream();
        toXServer = socket.getOutputStream();
        return "tcp:" + xServerAddress;
    }

    /**
     * Data arriving from the SSH peer (the forwarded X client) heading to the
     * local X server. The very first packet carries the X11 connection setup,
     * where we swap the advertised fake cookie for the real local cookie.
     */
    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        getLocalWindow().release(len);
        int length = (int) len;

        if (authRewritten) {
            toXServer.write(data, off, length);
            toXServer.flush();
            return;
        }

        // Accumulate until we can locate and replace the cookie in the setup packet
        byte[] combined = new byte[pending.length + length];
        System.arraycopy(pending, 0, combined, 0, pending.length);
        System.arraycopy(data, off, combined, pending.length, length);

        int end = X11SetupPacket.authDataEnd(combined, combined.length);
        if (end == X11SetupPacket.INCOMPLETE) {
            // Not enough bytes yet - keep buffering
            pending = combined;
            return;
        }

        X11SetupPacket.replaceCookie(combined, fakeCookie, realCookie);
        pending = new byte[0];
        authRewritten = true;
        toXServer.write(combined, 0, combined.length);
        toXServer.flush();
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new UnsupportedOperationException("x11 channel does not support extended data");
    }

    private void pumpXServerToPeer() {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = fromXServer.read(buf)) >= 0) {
                if (n > 0) {
                    toPeer.write(buf, 0, n);
                    toPeer.flush();
                }
            }
        } catch (IOException e) {
            // X server closed or error - fall through to channel close
        } finally {
            try {
                if (toPeer != null) {
                    toPeer.close();
                }
            } catch (IOException ignore) {
            }
            close(false);
        }
    }

    /**
     * EOF from the SSH peer (the forwarded X client closed its write side):
     * half-close only, like MINA's {@code TcpipServerChannel.handleEof} ->
     * {@code port.handleEof()} (shutdownOutputStream). Bytes still flowing from
     * the X server to the peer keep going; the channel is closed when the X
     * server side reaches EOF in {@link #pumpXServerToPeer()}.
     */
    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        shutdownOutputToXServer();
    }

    private void shutdownOutputToXServer() {
        try {
            if (toXServer != null) {
                toXServer.flush();
            }
            if (connection instanceof Socket) {
                Socket s = (Socket) connection;
                if (!s.isClosed() && !s.isOutputShutdown()) {
                    s.shutdownOutput();
                }
            } else if (connection instanceof SocketChannel) {
                SocketChannel ch = (SocketChannel) connection;
                if (ch.isOpen()) {
                    ch.shutdownOutput();
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("[X11] shutdownOutput to local X server failed: {}", e.getMessage());
            }
        }
    }

    private void closeConnection() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (IOException ignore) {
        }
    }
}
