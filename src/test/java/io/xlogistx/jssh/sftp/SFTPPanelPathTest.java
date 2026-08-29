package io.xlogistx.jssh.sftp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure path/message helpers that the SFTP workers use. The
 * worker threads only ever see a directory captured on the EDT, so all path
 * building goes through {@link SFTPPanel#joinRemote} - it must be a plain
 * function of its two arguments.
 */
public class SFTPPanelPathTest {

    @Test
    public void joinRemoteDoesNotDoubleTheRootSlash() {
        assertEquals("/etc", SFTPPanel.joinRemote("/", "etc"));
        assertEquals("/home/user/file.txt", SFTPPanel.joinRemote("/home/user", "file.txt"));
    }

    @Test
    public void joinRemoteKeepsNamesVerbatim() {
        // Names with spaces, parens and a leading '@' are joined as-is
        assertEquals("/srv/report (final).txt", SFTPPanel.joinRemote("/srv", "report (final).txt"));
        assertEquals("/node_modules/@types", SFTPPanel.joinRemote("/node_modules", "@types"));
    }

    @Test
    public void joinRemoteIsPureSoACapturedDirectoryIsStable() {
        // Simulates bug #1: the count pass and the download pass must resolve the
        // same path from the captured directory even if the "current" one moved on
        String captured = "/data/in";
        String current = "/data/out"; // where the user navigated to meanwhile
        String countPass = SFTPPanel.joinRemote(captured, "a.bin");
        String downloadPass = SFTPPanel.joinRemote(captured, "a.bin");
        assertEquals(countPass, downloadPass);
        assertNotEquals(SFTPPanel.joinRemote(current, "a.bin"), downloadPass);
    }

    @Test
    public void parentPath() {
        assertEquals("/", SFTPPanel.getParentPath("/"));
        assertEquals("/", SFTPPanel.getParentPath("/home"));
        assertEquals("/home", SFTPPanel.getParentPath("/home/user"));
        assertEquals("/a/b", SFTPPanel.getParentPath("/a/b/c"));
    }

    @Test
    public void parentPathOfRelativeOrBareNameIsRoot() {
        assertEquals("/", SFTPPanel.getParentPath("home"));
    }

    @Test
    public void causeMessageUnwrapsSwingWorkerExecutionException() {
        IOException cause = new IOException("Permission denied");
        assertEquals("Permission denied", SFTPPanel.causeMessage(new ExecutionException(cause)));
        assertEquals("plain", SFTPPanel.causeMessage(new IOException("plain")));
        // A cause with no message still yields something readable
        assertEquals("java.io.IOException", SFTPPanel.causeMessage(new ExecutionException(new IOException())));
    }
}
