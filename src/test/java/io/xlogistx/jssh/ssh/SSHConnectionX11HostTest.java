package io.xlogistx.jssh.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X11 display-host classification in {@link SSHConnection}. Pure functions, no
 * session needed.
 */
public class SSHConnectionX11HostTest {

    private static final String MAC_PREFIX = "/private/tmp/com.apple.launchd.AbCdEf/org.xquartz";

    @Test
    public void macOsPathStyleDisplayIsLocal() {
        // ConnectDialog splits "$DISPLAY" at the last ':' so the host is the socket prefix
        assertTrue(SSHConnection.isLocalX11Host(MAC_PREFIX));
        assertEquals(MAC_PREFIX + ":0", SSHConnection.x11SocketPathFor(MAC_PREFIX, 0));
        assertEquals(MAC_PREFIX + ":1", SSHConnection.x11SocketPathFor(MAC_PREFIX, 1));
    }

    @Test
    public void conventionalLocalNamesAreLocal() {
        assertTrue(SSHConnection.isLocalX11Host(null));
        assertTrue(SSHConnection.isLocalX11Host(""));
        assertTrue(SSHConnection.isLocalX11Host("localhost"));
        assertTrue(SSHConnection.isLocalX11Host("LOCALHOST"));
        assertTrue(SSHConnection.isLocalX11Host("unix"));
        assertTrue(SSHConnection.isLocalX11Host("127.0.0.1"));
        // Local hosts get a socket candidate (existence is checked at connect time)
        assertNotNull(SSHConnection.x11SocketPathFor("localhost", 0));
    }

    @Test
    public void remoteHostIsTcpOnly() {
        assertFalse(SSHConnection.isLocalX11Host("192.168.1.20"));
        assertFalse(SSHConnection.isLocalX11Host("xserver.example.com"));
        assertNull(SSHConnection.x11SocketPathFor("192.168.1.20", 0));
    }

    @Test
    public void splitX11DisplayForms() {
        assertArrayEquals(new String[] { "", "0" }, SSHConnection.splitX11Display(":0"));
        assertArrayEquals(new String[] { "localhost", "10" }, SSHConnection.splitX11Display("localhost:10.0"));
        assertArrayEquals(new String[] { "192.168.1.5", "1" }, SSHConnection.splitX11Display("192.168.1.5:1"));
        assertArrayEquals(new String[] { MAC_PREFIX, "0" }, SSHConnection.splitX11Display(MAC_PREFIX + ":0"));
        // Garbage display number → 0, host kept
        assertArrayEquals(new String[] { "host", "0" }, SSHConnection.splitX11Display("host:abc"));
    }

    @Test
    public void unreachableDisplayIsNotAvailable() {
        // No socket for display 9999 and nothing listening on 127.0.0.1:15999
        assertFalse(SSHConnection.isX11DisplayReachable(":9999"));
    }

    @Test
    public void pasteLineEndingOverrideBeatsDetection() {
        String win = "SSH-2.0-OpenSSH_for_Windows_9.5";
        String nix = "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13";
        assertEquals(io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.CR,
                SSHConnection.resolvePasteLineEnding("AUTO", win));
        assertEquals(io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.LF,
                SSHConnection.resolvePasteLineEnding(null, nix));
        assertEquals(io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.CRLF,
                SSHConnection.resolvePasteLineEnding("crlf", nix), "explicit choice wins, case-insensitive");
        assertEquals(io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.LF,
                SSHConnection.resolvePasteLineEnding("LF", win));
        assertEquals(io.xlogistx.jssh.terminal.TerminalPanel.LineEnding.CR,
                SSHConnection.resolvePasteLineEnding("nonsense", win), "unknown value → auto");
    }
}
