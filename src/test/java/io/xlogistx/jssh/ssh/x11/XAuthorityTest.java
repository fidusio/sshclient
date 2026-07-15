package io.xlogistx.jssh.ssh.x11;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the {@code ~/.Xauthority} cookie reader. Uses a temp file pointed to
 * by the XAUTHORITY environment override via a system property is not possible,
 * so these tests exercise the binary parser directly against a written file by
 * temporarily setting user.home.
 */
public class XAuthorityTest {

    private String savedHome;

    @AfterEach
    public void restoreHome() {
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
            savedHome = null;
        }
    }

    private static void writeEntry(ByteArrayOutputStream out, int family, String addr,
                                   String display, String name, byte[] cookie) {
        writeU16(out, family);
        writeChunk(out, addr.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeChunk(out, display.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeChunk(out, name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeChunk(out, cookie);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeChunk(ByteArrayOutputStream out, byte[] b) {
        writeU16(out, b.length);
        out.write(b, 0, b.length);
    }

    private Path useTempHome() throws IOException {
        Path home = Files.createTempDirectory("jssh-xauth-home");
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        return home;
    }

    @Test
    public void findsCookieForMatchingDisplay() throws IOException {
        // Skip if XAUTHORITY env is set - it would take precedence over user.home
        assumeTrue(System.getenv("XAUTHORITY") == null);

        Path home = useTempHome();
        byte[] cookie0 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] cookie1 = {21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36};

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeEntry(out, 256, "host", "0", "MIT-MAGIC-COOKIE-1", cookie0);
        writeEntry(out, 256, "host", "1", "MIT-MAGIC-COOKIE-1", cookie1);
        Files.write(home.resolve(".Xauthority"), out.toByteArray());

        assertArrayEquals(cookie1, XAuthority.findMagicCookie(1));
        assertArrayEquals(cookie0, XAuthority.findMagicCookie(0));
    }

    @Test
    public void returnsNullWhenNoFile() throws IOException {
        assumeTrue(System.getenv("XAUTHORITY") == null);
        useTempHome(); // empty temp home, no .Xauthority
        assertNull(XAuthority.findMagicCookie(0));
    }

    @Test
    public void fallsBackToFirstCookieForUnknownDisplay() throws IOException {
        assumeTrue(System.getenv("XAUTHORITY") == null);
        Path home = useTempHome();
        byte[] cookie0 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeEntry(out, 256, "host", "0", "MIT-MAGIC-COOKIE-1", cookie0);
        Files.write(home.resolve(".Xauthority"), out.toByteArray());

        // Display 7 not present -> first MIT-MAGIC-COOKIE-1 is returned as fallback
        assertArrayEquals(cookie0, XAuthority.findMagicCookie(7));
    }
}
