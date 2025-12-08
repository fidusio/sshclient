# JSSH - Java SSH Client

A modern GUI SSH client written in Java using Apache MINA SSHD.

## Features

- **Modern SSH Protocol Support**
  - Ed25519, ECDSA, RSA, DSA keys
  - curve25519, ECDH, Diffie-Hellman key exchange
  - ChaCha20-Poly1305, AES-GCM, AES-CTR ciphers
  - RSA-SHA2 signatures (rsa-sha2-256, rsa-sha2-512)

- **Terminal Emulation**
  - VT100/ANSI terminal emulator
  - 256 color support
  - Copy/paste support
  - Window resize

- **SFTP File Browser**
  - Dual-pane file browser
  - Upload/download files
  - Create/delete directories
  - File permissions display

- **Port Forwarding**
  - Local port forwarding (tunnel to remote)
  - Remote port forwarding (tunnel from remote)

- **Key Management**
  - Generate Ed25519, ECDSA, RSA keys
  - Import/export keys
  - View key fingerprints

- **Session Management**
  - Multiple tabbed sessions
  - Quick connect
  - Host key verification

## Requirements

- Java 11 or higher
- Maven 3.6+ (for building)

## Building

```bash
mvn clean package
```

This creates `target/jssh-1.0.0.jar` with all dependencies included.

## Running

```bash
# GUI mode
java -jar target/jssh-1.0.0.jar

# Quick connect
java -jar target/jssh-1.0.0.jar user@hostname

# With port
java -jar target/jssh-1.0.0.jar user@hostname:2222
```

## Usage

### Connecting

1. Click **File → Connect** or press **Ctrl+N**
2. Enter hostname, port, username
3. Choose authentication method:
   - Password authentication
   - Public key authentication (select key file)
4. Click **Connect**

### Quick Connect

1. Press **Ctrl+Q** or use toolbar
2. Enter `user@host` or `user@host:port`
3. Enter password when prompted

### SFTP File Transfer

1. Connect to a server
2. Click **Tools → SFTP Browser** or toolbar button
3. Navigate local and remote directories
4. Select files and click Upload/Download

### Port Tunnels

1. Connect to a server
2. Click **Tools → Port Tunnels**
3. Add local or remote port forward
4. Specify local and remote ports

### Key Management

1. Click **Tools → Key Manager**
2. Generate new keys or import existing
3. Export public key for `authorized_keys`

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+N | New connection |
| Ctrl+W | Close tab |
| Ctrl+Q | Exit |
| Ctrl+C | Copy (when text selected) |
| Ctrl+V | Paste |
| Ctrl+Shift+C | Copy in terminal |
| Ctrl+Shift+V | Paste in terminal |

## Terminal Escape Sequences

Supported:
- Cursor movement (CSI A/B/C/D/H/f)
- Erase display/line (CSI J/K)
- SGR colors (CSI m) including 256 colors
- Scroll region (CSI r)
- Insert/delete lines (CSI L/M)
- Window title (OSC 0/2)
- Application cursor keys (CSI ?1h/l)
- Alternate screen buffer (CSI ?1049h/l)

## Configuration Files

- `~/.ssh/known_hosts` - Known host keys
- `~/.ssh/id_*` - SSH key files

## Troubleshooting

### Connection timeout
- Check hostname and port
- Verify network connectivity
- Check firewall settings

### Authentication failed
- Verify username and password
- Check key file permissions (should be 600)
- Verify key is in server's authorized_keys

### Host key changed
- If legitimate: remove old key from known_hosts
- If unexpected: possible security issue!

## Dependencies

- Apache MINA SSHD 2.12.1
- EdDSA (net.i2p.crypto) 0.3.0
- SLF4J 2.0.9

## License

MIT License

## Credits

Built with [Apache MINA SSHD](https://mina.apache.org/sshd-project/)
