# CLAUDE.md

Guidance for AI assistants working in this repository.

## What this is

JSSH — a Swing GUI SSH client built on Apache MINA SSHD. Single Maven module,
~7k lines of Java. GUI app (no server component). Entry point:
`io.xlogistx.jssh.Main`.

## Build, run, test

```bash
mvn clean package          # builds target/jssh.jar (fat jar via assembly)
java -jar target/jssh.jar  # run the GUI
mvn test                   # run unit tests (needs Java 17+ for JUnit 5)
```

- **Java level:** compiled with `<release>8</release>` (value comes from the
  parent POM's `${jdk.version}`). The application must stay Java 8-compatible —
  do not use post-8 JDK APIs (e.g. `InputStream.readAllBytes`, `List.of`, `var`).
  `--release 8` will reject them at compile time.
- **Tests:** the parent POM sets `skipTests=true` globally; this module overrides
  it to `false`. Tests use JUnit 5 (Jupiter). To run a specific test outside
  Maven, use the JUnit Platform launcher, not `JUnitCore`.
- **Parent POM:** `io.xlogistx:xlogistx-mvn:1.0.0-SNAPSHOT` supplies most
  dependency versions (`${apache-ssh.version}`, `${bc.version}`,
  `${zoxweb-core.version}`, plugin versions). The project can't build without it
  installed in the local repo.
- **Offline/CI note:** this environment resolves Maven artifacts from a local
  repo; network access to Maven Central may be cert-blocked. Prefer `mvn -o`
  (offline) and expect some plugin providers (e.g. surefire test providers) to be
  absent offline.

## Layout

- `ssh/SSHConnection.java` — MINA client wrapper: connect, auth, shell, SFTP,
  port forwards, host-key verification, X11 setup. The hub of the app.
- `ssh/x11/` — client-side X11 forwarding (see below).
- `terminal/TerminalPanel.java` — VT100/ANSI parser + renderer (Swing). Pure
  logic is unit-testable headless.
- `ui/` — `MainFrame` (tabs, session lifecycle, detach/clone), `ConnectDialog`,
  `TunnelDialog`, `KeyManagerDialog`.
- `sftp/SFTPPanel.java` — dual-pane SFTP browser.
- `config/` — `JSSHConst` (all constants), `ConnectionConfig`/`ConnectionManager`
  (profiles in `~/.jssh/connections`), `KnownHostsManager` (`~/.jssh/known_hosts`).

## Conventions and gotchas

- **Swing threading:** network/SSH work runs off the EDT (SwingWorker or plain
  threads); any UI update from those must go through `SwingUtilities.invokeLater`
  or the `setStatus` helper. Don't call Swing setters from background threads.
- **Credentials:** passwords/passphrases are held as `char[]` and wiped on
  session close; convert to `String` only at the MINA auth call. Don't reintroduce
  long-lived `String` secrets.
- **Host key fingerprints:** use `KeyUtils.getFingerPrint(...)` (SSH wire format,
  matches `ssh-keygen -lf`). Do NOT hash `key.getEncoded()` (X.509) — that
  produces fingerprints that don't match OpenSSH.
- **MINA X11 (important):** MINA 2.16 has no client-side X11 forwarding — only the
  server/display-proxy half in `org.apache.sshd.server.x11.*`. The client side is
  hand-built in `ssh/x11/`:
  - `X11ChannelShell` sends `x11-req` inside `doOpenPty()` so it precedes the
    `shell` request (ordering is required for the server to set `$DISPLAY`).
  - `X11ChannelFactory` registers the `x11` channel type on the client.
  - `X11ClientChannel extends AbstractServerChannel` bridges to the local X server.
    **When overriding `doInit`, you MUST call `signalChannelOpenSuccess()` before
    `f.setOpened()`** — otherwise no channel-open confirmation is sent, the server
    never forwards data, and GUI apps connect but show no window (no error). Model
    on MINA's `TcpipServerChannel`.
- **Studying MINA internals:** the sources jars are in the local repo
  (`*-sources.jar`); unzip and read them rather than guessing at the API.

## Working style for this repo

- The maintainer runs the GUI/X11 tests manually on Windows with a real server;
  X11 and live-connection behavior can't be verified in the sandbox. State clearly
  what's verified vs. needs a real-world run.
- When an instruction is ambiguous (especially reverts / direction changes),
  confirm intent before editing.
- After nontrivial changes, run `mvn -o clean compile` (and `mvn test` when logic
  is touched) before declaring done.
