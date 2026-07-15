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
  - Remote port forwarding (tunnel from remote) — binds loopback by default, with
    an opt-in "Allow external connections" (0.0.0.0) checkbox

- **X11 Forwarding**
  - Forward remote GUI applications to a local X server
  - Client-side implementation over Apache MINA SSHD (accepts the server's `x11`
    channels and bridges to the local display) with MIT-MAGIC-COOKIE-1 handling

- **Key Management**
  - Generate Ed25519, ECDSA, RSA keys (via `ssh-keygen`)
  - Import/export keys
  - View key fingerprints (OpenSSH SHA-256 format, matches `ssh-keygen -lf`)

- **Session Management**
  - Multiple tabbed sessions, detach to separate window, clone session
  - Quick connect
  - Host key verification with a known-hosts store (`~/.jssh/known_hosts`)

## Requirements

- Java 8+ to run the application (the code is compiled with `--release 8`)
- Maven 3.6+ to build
- Java 17+ only if you want to run the unit tests (they use JUnit 5)

## Building

```bash
mvn clean package
```

This creates `target/jssh.jar` with all dependencies included.

The Maven compiler is pinned to Java 8 via `<release>8</release>` (inherited
`${jdk.version}` from the parent POM), so any accidental use of a post-8 API is a
hard compile error.

## Testing

Unit tests (JUnit 5) cover the terminal escape parser, connection-config
round-tripping, and the X11 cookie/Xauthority logic:

```bash
mvn test
```

The parent POM skips tests globally; this module re-enables them via
`<skipTests>false</skipTests>`. Running the tests requires Java 17+ (JUnit 5),
even though the application itself targets Java 8.

## Running

```bash
# GUI mode
java -jar jssh.jar

# Quick connect
java -jar jssh.jar user@hostname

# With port
java -jar jssh.jar user@hostname:2222
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
5. For remote forwards, tick **Allow external connections** only if you want the
   server-side port exposed on all interfaces (needs `GatewayPorts yes` on the
   server); otherwise it binds to loopback

### X11 Forwarding

1. Start a local X server:
   - **Windows:** VcXsrv or Xming (XLaunch → "Multiple windows" → "Start no
     client"). If you leave X server authentication enabled, X11 uses the cookie
     from `~/.Xauthority` / `$XAUTHORITY`; otherwise check "Disable access control".
   - **Linux:** already running (`$DISPLAY` is set)
   - **macOS:** XQuartz
2. Open **File → Connect** (not Quick Connect) → **Terminal** tab
3. Check **Enable X11 Forwarding** (display defaults to `localhost:0`)
4. Connect, then run a GUI program on the server (e.g. `xeyes`, `xclock`); the
   server must have `X11Forwarding yes` in `sshd_config`

X11 forwarding is not propagated to cloned sessions, and can only be enabled at
connect time (not after a session is already open).

### Key Management

1. Click **Tools → Key Manager**
2. Generate new keys or import existing
3. Export public key for `authorized_keys`

## Keyboard Shortcuts

| Shortcut     | Action                    |
|--------------|---------------------------|
| Ctrl+N       | New connection            |
| Ctrl+W       | Close tab                 |
| Ctrl+Shift+D | Detach tab to window      |
| Ctrl+Shift+C | Clone session / Copy in terminal |
| Ctrl+Q       | Exit                      |
| Ctrl+C       | Copy (when text selected) / interrupt |
| Ctrl+V       | Paste                     |
| Ctrl+Shift+V | Paste in terminal         |

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

- `~/.jssh/connections/*.properties` - Saved connection profiles
- `~/.jssh/known_hosts` - Known host keys (JSSH format, stores fingerprint + key)
- `~/.ssh/id_*` - SSH key files (used for public-key authentication and the Key Manager)
- `~/.Xauthority` / `$XAUTHORITY` - X11 cookie source when X11 forwarding is used

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

- Apache MINA SSHD
- BouncyCastle
- SLF4J

## How to use it

Make sure you have jre 1.8+ installed on your system.\
Get [jar-loader.jar](https://xlogistx.io/apps/jar-loader.jar)\
Get [jssh.jar](https://xlogistx.io/apps/jssh.jar)

Then type\
java -jar [jar-loader.jar](https://xlogistx.io/apps/jar-loader.jar) -jar [jssh.jar](https://xlogistx.io/apps/jssh.jar)

## License

MIT License

## Credits

Built with [Apache MINA SSHD](https://mina.apache.org/sshd-project/)
