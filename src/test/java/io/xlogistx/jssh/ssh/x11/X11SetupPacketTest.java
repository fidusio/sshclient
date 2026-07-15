package io.xlogistx.jssh.ssh.x11;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the X11 setup-packet cookie handling (the endian-sensitive parsing
 * and fake-to-real cookie replacement).
 */
public class X11SetupPacketTest {

    /** Builds a minimal X11 connection setup packet carrying the given cookie. */
    private static byte[] setupPacket(boolean bigEndian, String authName, byte[] cookie) {
        byte[] name = authName.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bigEndian ? 0x42 : 0x6C); // byte order
        out.write(0);                        // unused
        writeU16(out, 11, bigEndian);        // proto major
        writeU16(out, 0, bigEndian);         // proto minor
        writeU16(out, name.length, bigEndian);
        writeU16(out, cookie.length, bigEndian);
        writeU16(out, 0, bigEndian);         // unused
        out.write(name, 0, name.length);
        pad(out, name.length);
        out.write(cookie, 0, cookie.length);
        pad(out, cookie.length);
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v, boolean bigEndian) {
        int hi = (v >> 8) & 0xFF;
        int lo = v & 0xFF;
        if (bigEndian) {
            out.write(hi);
            out.write(lo);
        } else {
            out.write(lo);
            out.write(hi);
        }
    }

    private static void pad(ByteArrayOutputStream out, int len) {
        int pad = (4 - (len % 4)) % 4;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private static byte[] cookie(int seed) {
        byte[] c = new byte[16];
        for (int i = 0; i < 16; i++) {
            c[i] = (byte) (seed + i);
        }
        return c;
    }

    @Test
    public void replacesMatchingCookieBigEndian() {
        byte[] fake = cookie(1);
        byte[] real = cookie(100);
        byte[] pkt = setupPacket(true, "MIT-MAGIC-COOKIE-1", fake);

        assertEquals(pkt.length, X11SetupPacket.authDataEnd(pkt, pkt.length));
        assertTrue(X11SetupPacket.replaceCookie(pkt, fake, real));

        int off = X11SetupPacket.authDataOffset(pkt);
        byte[] got = new byte[16];
        System.arraycopy(pkt, off, got, 0, 16);
        assertArrayEquals(real, got);
    }

    @Test
    public void replacesMatchingCookieLittleEndian() {
        byte[] fake = cookie(5);
        byte[] real = cookie(200);
        byte[] pkt = setupPacket(false, "MIT-MAGIC-COOKIE-1", fake);

        assertTrue(X11SetupPacket.replaceCookie(pkt, fake, real));
        int off = X11SetupPacket.authDataOffset(pkt);
        byte[] got = new byte[16];
        System.arraycopy(pkt, off, got, 0, 16);
        assertArrayEquals(real, got);
    }

    @Test
    public void doesNotReplaceWhenCookieDiffers() {
        byte[] fake = cookie(1);
        byte[] real = cookie(100);
        byte[] other = cookie(50);
        byte[] pkt = setupPacket(true, "MIT-MAGIC-COOKIE-1", other);

        assertFalse(X11SetupPacket.replaceCookie(pkt, fake, real));
        int off = X11SetupPacket.authDataOffset(pkt);
        byte[] got = new byte[16];
        System.arraycopy(pkt, off, got, 0, 16);
        assertArrayEquals(other, got, "untouched when no match");
    }

    @Test
    public void incompleteHeaderReportsIncomplete() {
        assertEquals(X11SetupPacket.INCOMPLETE, X11SetupPacket.authDataEnd(new byte[5], 5));
    }

    @Test
    public void incompleteBodyReportsIncomplete() {
        byte[] pkt = setupPacket(true, "MIT-MAGIC-COOKIE-1", cookie(1));
        // One byte short of the full auth data
        assertEquals(X11SetupPacket.INCOMPLETE, X11SetupPacket.authDataEnd(pkt, pkt.length - 1));
    }

    @Test
    public void nullCookiesAreNoOp() {
        byte[] pkt = setupPacket(true, "MIT-MAGIC-COOKIE-1", cookie(1));
        assertFalse(X11SetupPacket.replaceCookie(pkt, null, cookie(2)));
        assertFalse(X11SetupPacket.replaceCookie(pkt, cookie(1), null));
    }
}
