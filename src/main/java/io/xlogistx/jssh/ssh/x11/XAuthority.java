package io.xlogistx.jssh.ssh.x11;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal reader for the OpenSSH/X11 {@code ~/.Xauthority} file, used to obtain
 * the real MIT-MAGIC-COOKIE-1 authorization for the local display so that
 * forwarded X11 connections are accepted by the local X server.
 *
 * <p>The file is a sequence of records, each big-endian:
 * <pre>
 *   uint16 family
 *   uint16 addrLen,  addr   (addrLen bytes)
 *   uint16 dispLen,  number (dispLen bytes, the display number as ASCII)
 *   uint16 nameLen,  name   (nameLen bytes, e.g. "MIT-MAGIC-COOKIE-1")
 *   uint16 dataLen,  data   (dataLen bytes, the cookie)
 * </pre>
 */
public final class XAuthority {

    private XAuthority() {
    }

    /**
     * Best-effort lookup of the MIT-MAGIC-COOKIE-1 cookie for the given display
     * number. Returns {@code null} if no Xauthority file exists, it can't be
     * parsed, or no matching entry is found (in which case the caller should
     * fall back to forwarding the connection unmodified).
     *
     * @param displayNumber the X11 display number (the N in host:N)
     * @return the cookie bytes, or {@code null} if unavailable
     */
    public static byte[] findMagicCookie(int displayNumber) {
        Path path = locate();
        if (path == null || !Files.exists(path)) {
            return null;
        }

        String wantDisplay = Integer.toString(displayNumber);
        byte[] firstCookie = null;

        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            while (true) {
                int family;
                try {
                    family = in.readUnsignedShort();
                } catch (EOFException eof) {
                    break;
                }
                byte[] addr = readChunk(in);
                byte[] number = readChunk(in);
                byte[] name = readChunk(in);
                byte[] data = readChunk(in);
                if (addr == null || number == null || name == null || data == null) {
                    break;
                }

                String proto = new String(name, java.nio.charset.StandardCharsets.US_ASCII);
                if (!"MIT-MAGIC-COOKIE-1".equals(proto)) {
                    continue;
                }
                // Remember the first cookie as a fallback for entries whose
                // display is unspecified (empty number field).
                if (firstCookie == null) {
                    firstCookie = data;
                }
                String disp = new String(number, java.nio.charset.StandardCharsets.US_ASCII);
                if (disp.isEmpty() || disp.equals(wantDisplay)) {
                    return data;
                }
            }
        } catch (IOException e) {
            return firstCookie;
        }

        return firstCookie;
    }

    private static byte[] readChunk(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readUnsignedShort();
        } catch (EOFException eof) {
            return null;
        }
        if (len < 0 || len > 0xFFFF) {
            return null;
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static Path locate() {
        String env = System.getenv("XAUTHORITY");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env);
        }
        String home = System.getProperty("user.home");
        if (home == null) {
            return null;
        }
        return Paths.get(home, ".Xauthority");
    }
}
