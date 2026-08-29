package io.xlogistx.jssh.sftp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SFTPPanel.FileItem}: the list label must decorate the name
 * (symlink marker, brackets, size suffix) without ever changing what
 * {@code getRealName()} returns, since that is what paths are built from.
 */
public class SFTPPanelFileItemTest {

    @Test
    public void symlinkDirectoryIsMarkedAndKeepsRealName() {
        SFTPPanel.FileItem link = new SFTPPanel.FileItem("docs", true, true, 0);
        assertEquals("[@docs]", link.name, "symlink dirs are shown as [@name]");
        assertEquals("docs", link.getRealName());
        assertTrue(link.isSymlink);
        assertTrue(link.isDirectory);
        assertFalse(link.isParent());
    }

    @Test
    public void realDirectoryStartingWithAtIsNotTreatedAsSymlink() {
        // e.g. node_modules/@types - a plain directory whose name begins with '@'
        SFTPPanel.FileItem dir = new SFTPPanel.FileItem("@types", true, false, 0);
        assertEquals("[@types]", dir.name);
        assertEquals("@types", dir.getRealName(), "leading '@' must not be stripped");
        assertFalse(dir.isSymlink);

        // And a symlink whose own name starts with '@' still round-trips
        SFTPPanel.FileItem atLink = new SFTPPanel.FileItem("@scope", true, true, 0);
        assertEquals("[@@scope]", atLink.name);
        assertEquals("@scope", atLink.getRealName());
    }

    @Test
    public void plainDirectoryLabel() {
        SFTPPanel.FileItem dir = new SFTPPanel.FileItem("src", true, false, 0);
        assertEquals("[src]", dir.name);
        assertEquals("src", dir.getRealName());
    }

    @Test
    public void fileLabelHasSizeSuffixButRealNameIsIntact() {
        SFTPPanel.FileItem f = new SFTPPanel.FileItem("report (final).txt", false, false, 2048);
        assertEquals("report (final).txt (2 kB)", f.name);
        // A name containing " (" must survive - the old label parser would have cut it
        assertEquals("report (final).txt", f.getRealName());

        SFTPPanel.FileItem symFile = new SFTPPanel.FileItem("latest.log", false, true, 10);
        assertEquals("latest.log", symFile.getRealName());
        assertTrue(symFile.isSymlink);
    }

    @Test
    public void parentEntry() {
        SFTPPanel.FileItem parent = SFTPPanel.FileItem.parent();
        assertTrue(parent.isParent());
        assertEquals("[..]", parent.name);
        assertEquals("..", parent.getRealName());
    }
}
