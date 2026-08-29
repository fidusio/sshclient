package io.xlogistx.jssh.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and default-handling tests for {@link ConnectionConfig}.
 */
public class ConnectionConfigTest {

    @Test
    public void roundTripPreservesAllFields() {
        ConnectionConfig original = new ConnectionConfig("my-profile");
        original.setHost("example.com");
        original.setPort(2222);
        original.setUsername("alice");
        original.setUseKeyAuth(true);
        original.setKeyFile("/home/alice/.ssh/id_ed25519");
        original.setTerminalType("xterm");
        original.setColumns(120);
        original.setRows(40);
        original.setX11Forwarding(true);
        original.setX11Display("localhost:1");

        Properties props = original.toProperties();
        ConnectionConfig restored = ConnectionConfig.fromProperties(props);

        assertEquals("my-profile", restored.getName());
        assertEquals("example.com", restored.getHost());
        assertEquals(2222, restored.getPort());
        assertEquals("alice", restored.getUsername());
        assertTrue(restored.isUseKeyAuth());
        assertEquals("/home/alice/.ssh/id_ed25519", restored.getKeyFile());
        assertEquals("xterm", restored.getTerminalType());
        assertEquals(120, restored.getColumns());
        assertEquals(40, restored.getRows());
        assertTrue(restored.isX11Forwarding());
        assertEquals("localhost:1", restored.getX11Display());
    }

    @Test
    public void fromEmptyPropertiesUsesDefaults() {
        ConnectionConfig config = ConnectionConfig.fromProperties(new Properties());

        assertEquals(JSSHConst.DEFAULT_SSH_PORT, config.getPort());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_TYPE, config.getTerminalType());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_COLS, config.getColumns());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_ROWS, config.getRows());
        assertFalse(config.isUseKeyAuth());
        assertFalse(config.isX11Forwarding());
    }

    @Test
    public void emptyNumericFieldsFallBackToDefaults() {
        Properties props = new Properties();
        props.setProperty("name", "p");
        props.setProperty("host", "h");
        props.setProperty("port", "");
        props.setProperty("columns", "   ");
        props.setProperty("rows", "");

        ConnectionConfig config = ConnectionConfig.fromProperties(props);

        assertEquals("p", config.getName());
        assertEquals("h", config.getHost());
        assertEquals(JSSHConst.DEFAULT_SSH_PORT, config.getPort());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_COLS, config.getColumns());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_ROWS, config.getRows());
    }

    @Test
    public void garbageNumericFieldsFallBackToDefaultsAndKeepOtherFields() {
        Properties props = new Properties();
        props.setProperty("name", "p");
        props.setProperty("host", "example.org");
        props.setProperty("username", "bob");
        props.setProperty("port", "twenty-two");
        props.setProperty("columns", "12x");
        props.setProperty("rows", "3.5");
        props.setProperty("useKeyAuth", "true");
        props.setProperty("keyFile", "/k");

        ConnectionConfig config = ConnectionConfig.fromProperties(props);

        assertEquals(JSSHConst.DEFAULT_SSH_PORT, config.getPort());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_COLS, config.getColumns());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_ROWS, config.getRows());
        // The rest of the profile survives
        assertEquals("example.org", config.getHost());
        assertEquals("bob", config.getUsername());
        assertTrue(config.isUseKeyAuth());
        assertEquals("/k", config.getKeyFile());
    }

    @Test
    public void outOfRangeNumericFieldsFallBackToDefaults() {
        Properties props = new Properties();
        props.setProperty("port", "70000");
        props.setProperty("columns", "-5");
        props.setProperty("rows", "99999999999"); // overflows int

        ConnectionConfig config = ConnectionConfig.fromProperties(props);

        assertEquals(JSSHConst.DEFAULT_SSH_PORT, config.getPort());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_COLS, config.getColumns());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_ROWS, config.getRows());
    }

    @Test
    public void validNumericFieldsAreStillParsed() {
        Properties props = new Properties();
        props.setProperty("port", " 2222 ");
        props.setProperty("columns", "132");
        props.setProperty("rows", "50");

        ConnectionConfig config = ConnectionConfig.fromProperties(props);

        assertEquals(2222, config.getPort());
        assertEquals(132, config.getColumns());
        assertEquals(50, config.getRows());
    }

    @Test
    public void managerKeepsProfileWithMalformedNumbers(@TempDir Path dir) throws Exception {
        // port= (empty) and a garbage columns value: the profile used to be
        // skipped entirely with only a stderr line
        Files.write(dir.resolve("broken-1.properties"), List.of(
                "name=broken",
                "host=broken.example.com",
                "port=",
                "username=carol",
                "columns=lots",
                "rows=24"), StandardCharsets.UTF_8);
        Files.write(dir.resolve("good-2.properties"), List.of(
                "name=good",
                "host=good.example.com",
                "port=2200"), StandardCharsets.UTF_8);

        ConnectionManager manager = new ConnectionManager(dir);

        assertTrue(manager.exists("broken"), "profile with malformed numbers must still load");
        assertTrue(manager.exists("good"));
        assertEquals(2, manager.getConnectionNames().size());

        ConnectionConfig broken = manager.get("broken");
        assertEquals("broken.example.com", broken.getHost());
        assertEquals("carol", broken.getUsername());
        assertEquals(JSSHConst.DEFAULT_SSH_PORT, broken.getPort());
        assertEquals(JSSHConst.DEFAULT_TERMINAL_COLS, broken.getColumns());
        assertEquals(24, broken.getRows());

        assertEquals(2200, manager.get("good").getPort());
    }

    @Test
    public void managerSaveAndReloadRoundTrip(@TempDir Path dir) throws Exception {
        ConnectionManager manager = new ConnectionManager(dir);
        ConnectionConfig config = new ConnectionConfig("rt");
        config.setHost("rt.example.com");
        config.setPort(2022);
        config.setColumns(100);
        manager.save(config);

        ConnectionManager reloaded = new ConnectionManager(dir);
        ConnectionConfig restored = reloaded.get("rt");
        assertNotNull(restored);
        assertEquals("rt.example.com", restored.getHost());
        assertEquals(2022, restored.getPort());
        assertEquals(100, restored.getColumns());
    }

    @Test
    public void pasteLineEndingDefaultsToAutoAndRoundTrips() {
        ConnectionConfig config = new ConnectionConfig("le");
        assertEquals("AUTO", config.getPasteLineEnding(), "default must be AUTO");

        config.setPasteLineEnding("crlf");
        assertEquals("CRLF", config.getPasteLineEnding(), "normalised to upper case");
        assertEquals("CRLF", ConnectionConfig.fromProperties(config.toProperties()).getPasteLineEnding());

        config.setPasteLineEnding("bogus");
        assertEquals("AUTO", config.getPasteLineEnding(), "unknown value falls back to AUTO");
        config.setPasteLineEnding(null);
        assertEquals("AUTO", config.getPasteLineEnding());

        // Old profile files without the key load as AUTO
        java.util.Properties props = new ConnectionConfig("old").toProperties();
        props.remove("pasteLineEnding");
        assertEquals("AUTO", ConnectionConfig.fromProperties(props).getPasteLineEnding());
    }

    @Test
    public void toStringPrefersName() {
        assertEquals("named", new ConnectionConfig("named").toString());

        ConnectionConfig noName = new ConnectionConfig();
        noName.setHost("h");
        noName.setUsername("u");
        noName.setPort(22);
        assertEquals("u@h:22", noName.toString());

        assertEquals("New Connection", new ConnectionConfig().toString());
    }
}
