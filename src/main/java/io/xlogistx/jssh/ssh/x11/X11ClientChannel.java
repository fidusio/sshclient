package io.xlogistx.jssh.ssh.x11;

import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;

/**
 * Client side of X11 forwarding: a channel opened by the SSH server (channel
 * type "x11") whenever a graphical program on the server connects to the
 * forwarded display. This channel connects out to the user's real local X
 * server and pipes bytes in both directions.
 *
 * <p>MINA SSHD ships only the display-proxy direction (see
 * {@code org.apache.sshd.server.x11.DefaultX11ForwardSupport}); this class
 * provides the missing client acceptor direction, modeled on MINA's own
 * {@code TcpipServerChannel} / {@code ChannelForwardedX11}.
 */
public class X11ClientChannel extends AbstractServerChannel {

    private final InetSocketAddress xServerAddress;
    private final int connectTimeoutMs;
    private final byte[] fakeCookie;   // what we advertised to the server (may be null)
    private final byte[] realCookie;   // local X server's cookie (may be null)

    private Socket socket;
    private OutputStream toXServer;
    private ChannelOutputStream toPeer;

    // First-packet cookie replacement state
    private boolean authRewritten;
    private byte[] pending = new byte[0];

    public X11ClientChannel(InetSocketAddress xServerAddress, int connectTimeoutMs,
                            byte[] fakeCookie, byte[] realCookie) {
        super("x11", Collections.emptyList(), null);
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
            socket = new Socket();
            socket.connect(xServerAddress, connectTimeoutMs);
            socket.setTcpNoDelay(true);
            toXServer = socket.getOutputStream();
            toPeer = new ChannelOutputStream(this, getRemoteWindow(), log, SshConstants.SSH_MSG_CHANNEL_DATA, true);

            // Ensure the local X server socket is closed whenever the SSH channel closes
            addCloseFutureListener(future -> closeSocket());

            Thread pump = new Thread(this::pumpXServerToPeer, "x11-forward-" + getChannelId());
            pump.setDaemon(true);
            pump.start();

            if (log.isDebugEnabled()) {
                log.debug("[X11] connected to local X server {} (cookieRewrite={})",
                        xServerAddress, canRewriteCookie() ? "yes" : "passthrough");
            }
            // Required: tells MINA to send SSH_MSG_CHANNEL_OPEN_CONFIRMATION so the
            // server starts forwarding X11 data to us. Without this the channel
            // stays half-open and no data ever arrives.
            signalChannelOpenSuccess();
            f.setOpened();
        } catch (IOException e) {
            log.warn("[X11] failed to connect to local X server {}: {}", xServerAddress, e.getMessage());
            f.setException(e);
            close(true);
        }
        // A connect failure keeps the warn above - it's an actionable error, not noise
        return f;
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
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
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

    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        closeSocket();
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignore) {
        }
    }
}
