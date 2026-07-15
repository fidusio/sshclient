package io.xlogistx.jssh.config;

import org.junit.jupiter.api.Test;

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
