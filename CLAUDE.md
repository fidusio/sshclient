# CLAUDE.md

Guidance for AI assistants working in this repository.

## What this is

JSSH — a Swing GUI SSH client built on Apache MINA SSHD. Single Maven module,
~9k lines of Java. GUI app (no server component). Entry point:
`io.xlogistx.jssh.Main`.

## Build, run, test

```bash
mvn -o clean package        # builds target/jssh.jar (fat jar via assembly)
java -jar target/jssh.jar   # run the GUI
mvn -o clean test-compile   # compile main + tests (the reliable offline check)
```

- **Java level:** compiled with `--release 25`, set via `<jdk.version>25</jdk.version>`
  in this module's `pom.xml` (overriding the parent's `8`). The app requires
  Java 25+ to run. The bump from 8 was needed for `java.net.UnixDomainSocketAddress`
  (X11 forwarding to the local X server socket). Modern APIs are fine.
- **Parent POM:** `io.xlogistx:xlogistx-mvn:1.0.0-SNAPSHOT` supplies most
  dependency versions (`${apache-ssh.version}`, `${bc.version}`,
  `${zoxweb-core.version}`, `${commonmark.version}`, plugin versions). The
  project can't build without it installed in the local repo. The local repo is
  `D:/dev/data/java/.m2/repository` (set in `~/.m2/settings.xml`), not `~/.m2`.
- **Offline/CI note:** network access to Maven Central may be cert-blocked.
  Prefer `mvn -o`. A first build that needs a not-yet-cached dependency must run
  on a networked machine.
- **Tests (JUnit 5, ~90 of them) — `mvn test` does NOT work offline:** the
  parent sets `skipTests=true` (this module overrides it to `false`), but the
  surefire JUnit Platform provider (`surefire-junit-platform`) is not in the
  offline repo, so `mvn -o test` fails at the surefire step even though
  compilation succeeded. Run the suite through the JUnit Platform launcher
  instead: compile with `mvn -o -q test-compile`, get the classpath with
  `mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test`,
  add `target/classes;target/test-classes` and the
  `org/junit/platform/junit-platform-launcher/6.1.2` jar from the local repo,
  and run a tiny `main` that uses `LauncherFactory.create().execute(...)` with
  `selectPackage("io.xlogistx.jssh")` and a `SummaryGeneratingListener`. Pass
  `-Djava.awt.headless=true -Dfile.encoding=UTF-8`. If you compile outside
  Maven, `src/main/resources/jssh-version.properties` is Maven-filtered
  (`${project.version}` etc.) and `JSSHConst`'s static init throws on the
  raw placeholders — substitute them (or copy `target/classes`).
- All tests are headless; UI classes are exercised only via package-private
  static helpers (`TerminalPanel.normalizeForPaste`, `metaSequence`,
  `SFTPPanel.FileItem`, `KeyManagerDialog.generateKeyPairFiles`, …). Keep it that
  way: put pure logic in a static/package-private method and test that.

## Layout

- `ssh/SSHConnection.java` — MINA client wrapper: connect (timeout excludes time
  spent in the host-key dialog; future is cancelled on timeout), auth (identities
  are removed from the session after `auth().verify()`), shell, SFTP, port
  forwards, host-key verification, X11 setup, host line-ending detection
  (`getHostLineEnding` / `resolvePasteLineEnding`), X11 reachability
  (`isX11Available` checks the socket/TCP port, not just `$DISPLAY`).
- `ssh/x11/` — client-side X11 forwarding (see below).
- `terminal/TerminalPanel.java` — VT100/ANSI parser + renderer (Swing). Pure
  logic is unit-testable headless. Handles UTF-8 decoding with validation,
  16-colour rendering (256/truecolor SGR are mapped to the nearest palette
  entry), scroll regions, bracketed paste (mode 2004), paste line-ending
  normalisation (`LineEnding` enum), AltGr and Alt=Meta keyboard handling.
- `ui/MainFrame.java` — tabs, session lifecycle, detach/clone, Help viewer.
  **All connect paths go through `MainFrame.openSession` → `attachShell`**
  (`ConnectDialog`, quick connect, clone, reconnect): network work in
  `SwingWorker.doInBackground`, `TerminalPanel` construction and all Swing
  wiring in `done()`. `SessionSpec` is the immutable snapshot (creds cloned,
  term type/size, X11, paste line ending) taken on the EDT before any thread
  starts; `SessionTab` remembers the same settings so clones reproduce them.
- `ui/ConnectDialog.java` — profile editor + connect. Snapshots every Swing
  field into a `SessionSpec` on the EDT; Connect is disabled while in flight;
  Cancel/close aborts the in-flight `SSHConnection` and discards a late success.
- `ui/KeyManagerDialog.java` — generates keys **in-process** (MINA
  `OpenSSHKeyPairResourceWriter` + BouncyCastle; Ed25519/ECDSA/RSA, passphrase
  via `OpenSSHKeyEncryptionContext`, 0600 on POSIX). No `ssh-keygen` dependency.
- `ui/TunnelDialog.java`, `sftp/SFTPPanel.java` — dual-pane SFTP browser. Every
  SFTP call runs in a `SwingWorker` (constructor opens the channel async and
  shows "connecting…"); directories are captured on the EDT and passed to the
  worker. `FileItem` stores the real filename (`realName`) and derives its label
  (`[dir]`, `[@symlinkdir]`, `file (12 kB)`) — never parse the label back.
- `config/` — `JSSHConst` (all constants), `ConnectionConfig`/`ConnectionManager`
  (profiles in `~/.jssh/connections`; malformed numeric fields fall back to
  defaults instead of dropping the profile), `KnownHostsManager`
  (`~/.jssh/known_hosts`, keyed by **(host[:port], keyType)** like OpenSSH —
  a different key *type* is UNKNOWN, not CHANGED; legacy type-less lines still
  load, match any type, and are rewritten on first match).
- `resources/how-to.md` — the in-app user guide. **Help → How to Use JSSH**
  (`MainFrame.showHowTo`) reads it from the classpath, renders Markdown → HTML
  with `org.commonmark:commonmark` **plus the `gfm-tables`, `gfm-strikethrough`
  and `task-list-items` extensions** (they must be registered on both the
  `Parser` and the `HtmlRenderer`, or pipe tables come out as literal `|` text),
  and shows it in a `JEditorPane` dialog. `JEditorPane` is HTML 3.2 / limited
  CSS, so keep the guide simple. `commonmark.version` comes from the parent POM
  (0.30.0 at the time of writing); all four artifacts are in the offline repo.

## Conventions and gotchas

- **Swing threading:** network/SSH work runs off the EDT (SwingWorker or plain
  threads); any UI update from those must go through `SwingUtilities.invokeLater`
  or the `setStatus` helper. Don't call Swing getters *or* setters from
  background threads — snapshot field values on the EDT first (see `SessionSpec`).
  `attachShell` throws if called off the EDT.
- **Credentials:** passwords/passphrases are held as `char[]` and wiped on
  session close; convert to `String` only at the MINA auth call, and remove the
  identity from the session afterwards. Don't reintroduce long-lived `String`
  secrets. `ConnectDialog` and `SessionSpec` wipe their own copies.
- **Host key fingerprints:** use `KeyUtils.getFingerPrint(...)` (SSH wire format,
  matches `ssh-keygen -lf`). Do NOT hash `key.getEncoded()` (X.509) — that
  produces fingerprints that don't match OpenSSH.
- **Terminal I/O encoding:** everything sent to the remote goes through
  `TerminalPanel.encodeForRemote` (UTF-8). Never `OutputStream.write(char)` or
  `String.getBytes()` with the platform charset. Incoming bytes are decoded by
  the panel's own UTF-8 state machine; invalid/overlong/surrogate/out-of-range
  sequences become U+FFFD and must never throw (a thrown exception kills the
  EDT runnable and drops the rest of the chunk).
- **Paste:** clipboard line breaks are normalised to one terminator per line
  (`normalizeForPaste`) — the per-profile "Paste newline" setting or, for AUTO,
  `detectHostLineEnding(serverBanner)` (Windows OpenSSH → CR, else LF). When
  the remote app has enabled mode 2004 the paste is wrapped in `ESC[200~ … ESC[201~`.
- **Keyboard:** AltGr is reported as Ctrl+Alt on Windows — `isAltGr(e)` must gate
  every Ctrl+letter branch or non-US layouts break. Alt+printable is Meta
  (`metaSequence` → `ESC`+key) and is consumed, so menu mnemonics don't fire
  while a terminal has focus. Ctrl+C sends SIGINT unless there is a selection
  that has *not* already been auto-copied on mouse release.
- **Blink timer:** `TerminalPanel` starts its `javax.swing.Timer` in `addNotify`
  and stops it in `removeNotify`/`dispose()`; `SessionTab.close()` calls
  `dispose()`. A running Swing timer pins the panel (and 10k scrollback lines).
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
    on MINA's `TcpipServerChannel`. `handleEof` half-closes (shutdownOutput), it
    does not close the socket.
  - Transport: connects to the local X server's **Unix-domain socket** for a local
    display (`/tmp/.X11-unix/X<n>` on Linux; on macOS `$DISPLAY` is a launchd
    *path* like `/private/tmp/com.apple.launchd.XXXX/org.xquartz:0` and the socket
    file really is named `…:0` — `isLocalX11Host`/`x11SocketPathFor` handle the
    `/`-prefixed form), like `ssh -X`, and falls back to TCP `127.0.0.1:6000+n` for
    a remote display host or where no socket exists (Windows/VcXsrv).
  - If no local MIT-MAGIC-COOKIE is found (`XAuthority`), forwarding still starts
    but the X server will reject clients; `SSHConnection.getX11Warning()` carries a
    one-line explanation that `attachShell` prints into the terminal.
- **Studying MINA internals:** the sources jars are in the local repo
  (`*-sources.jar`); unzip and read them rather than guessing at the API.

## Working style for this repo

- The maintainer runs the GUI/X11 tests manually on Windows with a real server;
  X11 and live-connection behavior can't be verified in the sandbox. State clearly
  what's verified vs. needs a real-world run.
- When an instruction is ambiguous (especially reverts / direction changes),
  confirm intent before editing.
- After nontrivial changes, run `mvn -o clean test-compile` and the JUnit suite
  (see above) before declaring done.
- **Editing tooling gotcha (this environment):** Bash heredocs and inline
  Python/sed one-liners mangle backslash escapes — `\r`, `\n`, `\t`, `\u001b`
  arrive as the literal control characters and produce "unclosed string literal"
  or invisible ESC bytes in Java source. Use the dedicated Read/Edit/Write tools
  for Java edits, or write `(char) 0x1B`, `chr(27)` etc. instead of escapes.
  Several source files have trailing whitespace on blank lines, so exact-match
  edits of multi-line blocks may need whitespace-tolerant matching.
- Parallel agents work fine if each owns a disjoint set of files and **none of
  them runs `mvn`** (concurrent builds collide on `target/`); compile with
  `javac` into per-agent output dirs instead.
