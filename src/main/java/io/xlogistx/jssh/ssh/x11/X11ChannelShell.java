package io.xlogistx.jssh.ssh.x11;

import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.PtyChannelConfigurationHolder;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;

import java.io.IOException;
import java.util.Map;

/**
 * A shell channel that requests X11 forwarding. The {@code x11-req} must reach
 * the server <em>before</em> the {@code shell} request, otherwise the server
 * allocates the forwarded display but never injects {@code $DISPLAY} into the
 * (already started) shell process - which is why sending it after the shell
 * request leaves {@code $DISPLAY} unset.
 *
 * <p>MINA's {@link ChannelShell#doOpen()} sends the shell request immediately
 * after {@code doOpenPty()}. Overriding {@code doOpenPty()} to append the
 * {@code x11-req} places it after pty-req/env and before {@code shell}, matching
 * what OpenSSH/PuTTY do.
 */
public class X11ChannelShell extends ChannelShell {

    private final String authCookieHex;
    private final int screen;

    public X11ChannelShell(PtyChannelConfigurationHolder configHolder, Map<String, ?> env,
                           String authCookieHex, int screen) {
        super(configHolder, env);
        this.authCookieHex = authCookieHex;
        this.screen = screen;
    }

    @Override
    protected void doOpenPty() throws IOException {
        super.doOpenPty();

        Session session = getSession();
        if (log.isDebugEnabled()) {
            log.debug("doOpenPty({}) send SSH_MSG_CHANNEL_REQUEST x11-req", this);
        }
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, 64);
        buffer.putUInt(getRecipient());
        buffer.putString("x11-req");
        buffer.putBoolean(false);   // want-reply
        buffer.putBoolean(false);   // single-connection = false (allow multiple)
        buffer.putString("MIT-MAGIC-COOKIE-1");
        buffer.putString(authCookieHex);
        buffer.putUInt(screen);
        writePacket(buffer);
    }
}
