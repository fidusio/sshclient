package io.xlogistx.jssh.ssh.x11;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.Session;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Registers the "x11" channel type on the client so that channels opened by the
 * server for X11 forwarding are accepted and bridged to the local X server via
 * {@link X11ClientChannel}. Without this factory MINA rejects incoming x11
 * channels with SSH_OPEN_UNKNOWN_CHANNEL_TYPE.
 */
public class X11ChannelFactory implements ChannelFactory {

    public static final String X11_CHANNEL_TYPE = "x11";

    private final InetSocketAddress xServerAddress;
    private final int connectTimeoutMs;
    private final byte[] fakeCookie;
    private final byte[] realCookie;

    public X11ChannelFactory(InetSocketAddress xServerAddress, int connectTimeoutMs,
                             byte[] fakeCookie, byte[] realCookie) {
        this.xServerAddress = xServerAddress;
        this.connectTimeoutMs = connectTimeoutMs;
        this.fakeCookie = fakeCookie;
        this.realCookie = realCookie;
    }

    @Override
    public String getName() {
        return X11_CHANNEL_TYPE;
    }

    @Override
    public Channel createChannel(Session session) throws IOException {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(X11ChannelFactory.class);
        if (log.isDebugEnabled()) {
            log.debug("[X11] server opened an x11 channel; bridging to local X server {} (localCookie={})",
                    xServerAddress, realCookie != null ? "present" : "none");
        }
        return new X11ClientChannel(xServerAddress, connectTimeoutMs, fakeCookie, realCookie);
    }
}
