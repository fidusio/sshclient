package io.xlogistx.jssh.ssh.x11;

/**
 * Helpers for the X11 connection-setup packet, isolated from channel/IO code so
 * the (fiddly, endian-sensitive) cookie handling can be unit tested.
 *
 * <p>X11 setup packet layout:
 * <pre>
 *   [0]    byte order ('B' = big-endian, 'l' = little-endian)
 *   [1]    unused
 *   [2..3] protocol major version
 *   [4..5] protocol minor version
 *   [6..7] auth-protocol-name length  (n)
 *   [8..9] auth-protocol-data length  (d)   &lt;- the cookie length
 *   [10..11] unused
 *   [12 ..]  auth-protocol-name, padded to 4 bytes
 *            auth-protocol-data (cookie), padded to 4 bytes
 * </pre>
 */
public final class X11SetupPacket {

    /** Returned by {@link #authDataEnd} when the packet is not yet complete. */
    public static final int INCOMPLETE = -1;

    private X11SetupPacket() {
    }

    /**
     * Returns the byte offset just past the auth cookie (i.e. the number of
     * header+auth bytes), or {@link #INCOMPLETE} if {@code b} does not yet hold
     * the full setup header and auth fields.
     */
    public static int authDataEnd(byte[] b, int len) {
        if (len < 12) {
            return INCOMPLETE;
        }
        boolean bigEndian = b[0] == 0x42; // 'B'
        int nameLen = readU16(b, 6, bigEndian);
        int dataLen = readU16(b, 8, bigEndian);
        int end = 12 + pad4(nameLen) + pad4(dataLen);
        return len < end ? INCOMPLETE : end;
    }

    /** Offset of the auth cookie within the setup packet. */
    public static int authDataOffset(byte[] b) {
        boolean bigEndian = b[0] == 0x42;
        int nameLen = readU16(b, 6, bigEndian);
        return 12 + pad4(nameLen);
    }

    /** Length of the auth cookie declared in the setup packet. */
    public static int authDataLength(byte[] b) {
        boolean bigEndian = b[0] == 0x42;
        return readU16(b, 8, bigEndian);
    }

    /**
     * If the packet's auth cookie equals {@code fake}, replace it in place with
     * {@code real}. No-op (returns false) when cookies are missing, differ in
     * length, or the packet's cookie does not match {@code fake}.
     *
     * @return true if a replacement was made
     */
    public static boolean replaceCookie(byte[] b, byte[] fake, byte[] real) {
        if (fake == null || real == null || fake.length == 0 || fake.length != real.length) {
            return false;
        }
        int off = authDataOffset(b);
        int dataLen = authDataLength(b);
        if (dataLen != fake.length || off + dataLen > b.length) {
            return false;
        }
        for (int i = 0; i < fake.length; i++) {
            if (b[off + i] != fake[i]) {
                return false;
            }
        }
        System.arraycopy(real, 0, b, off, real.length);
        return true;
    }

    static int readU16(byte[] b, int off, boolean bigEndian) {
        int hi = b[off] & 0xFF;
        int lo = b[off + 1] & 0xFF;
        return bigEndian ? (hi << 8) | lo : (lo << 8) | hi;
    }

    static int pad4(int n) {
        return n + ((4 - (n % 4)) % 4);
    }
}
