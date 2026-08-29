# How to Use JSSH

JSSH is a GUI SSH client. This guide covers connecting, transferring files,
tunneling ports, X11 forwarding, and managing keys.

## Connecting to a server

1. Click **Connect** on the toolbar, or **File → Connect** (**Ctrl+N**).
2. Enter the **Host**, **Port** (default 22), and **Username**.
3. Choose an authentication method:
   - **Password** — type your password.
   - **Public key** — check *Use public key authentication* and select your key
     file (e.g. `~/.ssh/id_ed25519`). Enter the passphrase if the key has one.
4. Optionally adjust the **Terminal** tab (terminal type, size, X11 forwarding).
5. Click **Connect**.

The first time you connect to a host, JSSH shows the server's key fingerprint
(OpenSSH SHA-256 format, matching `ssh-keygen -lf`). Accept it to remember the
host; it is stored in `~/.jssh/known_hosts`. If a known host's key later changes,
you get a warning — investigate before accepting.

### Saved connections (profiles)

In the Connect dialog, use **Save** / **Save As...** to store the current
settings as a named profile, then pick it from the dropdown next time. Profiles
live in `~/.jssh/connections/`.

### Quick Connect

**File → Quick Connect** (or the toolbar) accepts `user@host`, `user@host:port`,
or `user@[ipv6]:port`, then prompts for a password.

## Working in the terminal

- Standard VT100/ANSI terminal with scrollback and 256-color support.
- **Copy/paste:** select text to copy; **Ctrl+Shift+V** (or right-click) to
  paste. **Ctrl+Shift+C** copies the selection in the terminal. Line endings in
  pasted text are converted to the remote host's convention (LF for Unix-like
  hosts, CR for Windows OpenSSH) — override it per profile with the
  **Paste newline** setting on the Terminal tab of the connect dialog — and
  bracketed paste is honoured so editors such as vim/nano don't auto-indent
  pasted blocks.
- **Alt+key** sends Meta (ESC + key) like xterm, so readline/emacs bindings
  such as `M-b`, `M-f`, `M-.` work. While a terminal has focus this takes
  precedence over the menu mnemonics (use the mouse or F10 for the menus).
- **Ctrl+C** copies when text is selected, otherwise sends an interrupt.
- **Shift+PageUp / PageDown** scrolls through history.
- Resize the window to resize the remote terminal.

## Tabs, detach, and clone

- Each connection is a tab. Close a tab with **Ctrl+W**.
- **Detach** (**Ctrl+Shift+D**) moves the current tab into its own window.
- **Clone** (**Ctrl+Shift+C** from the menu) opens a second connection to the
  same server reusing your credentials.

## SFTP file transfer

1. Connect, then click **SFTP** on the toolbar (or **Tools → SFTP Browser**).
2. The left pane is your local machine, the right pane is the server.
3. Double-click folders to navigate; use **ChDir / MkDir / Rename / Delete**.
4. Select files and click **-->** to upload or **<--** to download. Directories
   transfer recursively.

In a detached window you can also toggle an embedded SFTP panel with
**Ctrl+F** (**View → Show SFTP Browser**).

## Port tunnels

Open **Tools → Port Tunnels** on a connected session.

- **Local forward** — listens on your machine and forwards through the server.
  Example: local `8080` → `remote-db:3306`, so `localhost:8080` reaches the
  database via the server.
- **Remote forward** — listens on the server and forwards back to your machine.
  By default the server-side port binds to loopback; tick **Allow external
  connections** only if you want it reachable from other machines (this needs
  `GatewayPorts yes` in the server's `sshd_config`).

Select a tunnel and click **Remove** to stop it.

## X11 forwarding (remote GUI apps)

Run graphical programs from the server on your local display.

1. Make sure a local X server is running:
   - **Linux:** already running.
   - **macOS:** start **XQuartz**.
   - **Windows:** start **VcXsrv** or **Xming** (XLaunch → *Multiple windows* →
     *Start no client*; if you leave authentication on, JSSH uses your
     `~/.Xauthority` cookie, otherwise check *Disable access control*).
2. In the Connect dialog, open the **Terminal** tab and check
   **Enable X11 Forwarding** (display defaults to `localhost:0`).
3. Connect. The server needs `X11Forwarding yes` in `sshd_config`.
4. Run a GUI app on the server, e.g. `xeyes` or `xclock` — it appears on your
   screen.

On Linux and macOS, JSSH connects to the X server's Unix-domain socket just like
`ssh -X`, so no extra TCP setup is needed. X11 must be enabled at connect time
and is not carried into cloned sessions.

## Key manager

**Tools → Key Manager** lets you:

- **Generate** Ed25519, ECDSA, or RSA keys in OpenSSH format (built in — no
  `ssh-keygen` needed; the passphrase never leaves the process).
- **Import** an existing private key into `~/.ssh`.
- **Export public key** to copy into a server's `authorized_keys`.
- View key fingerprints.

## Keyboard shortcuts

| Shortcut       | Action                                |
|----------------|---------------------------------------|
| Ctrl+N         | New connection                        |
| Ctrl+W         | Close tab                             |
| Ctrl+Shift+D   | Detach tab to a window                |
| Ctrl+Shift+C   | Clone session / copy in terminal      |
| Ctrl+Shift+V   | Paste in terminal                     |
| Ctrl+C         | Copy selection / interrupt            |
| Ctrl+Q         | Exit                                  |

## Troubleshooting

- **Connection timeout** — check host/port, network, and firewall.
- **Authentication failed** — verify username/password, key permissions
  (private key should be `600`), and that your public key is in the server's
  `authorized_keys`.
- **Host key changed warning** — if you expected it (server rebuilt), remove the
  old entry; if not, it could indicate a man-in-the-middle — do not accept.
- **X11: no window appears** — confirm `X11Forwarding yes` on the server, that a
  local X server is running, and that `echo $DISPLAY` in the session is set.
