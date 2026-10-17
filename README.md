# SessionIQ

Client-side session analytics and history for **Minecraft Java Edition 1.21.11 (Fabric)**. SessionIQ records your play sessions, server visits, received chat, disconnects, ping and memory, and keeps all of it on your computer in plain JSON files.

- **Minecraft** 1.21.11 · **Fabric Loader** ≥ 0.17 (built against 0.19.5) · **Fabric API** 0.141.6+1.21.11 (any 1.21.11 build) · **Java** 21
- Client-only; works on any server without a server-side mod. No network access, accounts, API keys or telemetry.

## Install

1. Install Fabric Loader for 1.21.11 and Fabric API.
2. Copy `sessioniq-1.0.0.jar` into `.minecraft/mods/`.
3. Start the game. Recording begins automatically.

## Using it

- Press **U** to open the dashboard. Change the key in *Settings › General › Open SessionIQ* (press **Change**, then the new key), or in *Options › Controls › Key Binds › SessionIQ*. The settings page warns about conflicting bindings.
- **Sections:** Overview, Sessions, Servers, Chat History, Disconnects, Timeline, Statistics, Settings. Detail pages (a session, a server, a message, a disconnect event, a day on the timeline) open on top. **Esc** or the back arrow returns, and filters are kept.
- **Keyboard:** Ctrl+F focuses the search field, ↑/↓ and Enter work in lists, Tab moves between fields, Ctrl+C copies the highlighted chat message, and ←/→ change the day on the Timeline.

## Data

Everything lives in `.minecraft/config/sessioniq/` (a `README.txt` there describes it too):

| Path | Contents |
|---|---|
| `sessions/YYYY-MM/<id>.json` | One file per session: visits, disconnect events, deaths, ping/memory summaries (authoritative) |
| `chat/<id>/part-NNNNN.jsonl` | Received chat for a session, append-only JSON Lines, 5,000 messages per partition |
| `samples/<id>.jsonl` | Ping and memory samples |
| `servers/registry.json` | Nicknames and groups you assigned |
| `state.json` | The session being recorded; if it is still there at the next launch, that session is recovered |
| `config.json`, `themes.json`, `manifest.json` | Preferences, custom themes, storage schema version |
| `backups/`, `exports/`, `quarantine/` | Backup archives, default export folder, untouched copies of damaged files |

Timestamps are UTC epoch milliseconds; they are converted to your time zone only for display. Durations come from a monotonic clock, so changing the system clock does not distort them. Files are written to a temporary file, flushed, and atomically renamed. Chat is appended and never rewritten wholesale. A damaged file is copied to `quarantine/` and skipped, so the rest of your history still loads. History is kept until you delete it.

### Definitions

- **Playtime:** time connected to a server or inside a single-player world. Visits never overlap, so nothing is counted twice.
- **Active playtime:** playtime minus time the game was paused.
- **Application time:** how long Minecraft was running.
- **Idle time:** application time minus playtime (menus, the server list, loading screens).

Statistics split time at local midnight in your display time zone, so daylight-saving changes are handled correctly.

### Disconnect classification

Disconnects are classified from what the client actually observed, with a certainty of *Confirmed*, *Inferred* or *Unknown*:

- **Intentional:** the client sent the quit reason itself (Disconnect / Save and Quit, closing the game).
- **Server kick / server closed:** the server sent an explicit disconnect message.
- **Timeout:** the client's read timeout expired.
- **Network failure:** the network layer reported an exception.
- **Connection lost (inferred):** the remote side closed the connection without a message.
- **Failed connection / cancelled / transfer / protocol error / abnormal termination:** each recorded when the evidence matches.

Otherwise the event is recorded as **Unknown**. After a crash, an interrupted session is closed at its last recorded activity. A crash report written at that time is cited as evidence, but no cause is assumed.

## Privacy

- Only messages the server shows in your chat box are archived (player chat and system messages, not the action bar).
- Commands you type, their arguments, anything else you type, and passwords are never recorded.
- Server echoes of your commands are discarded as well: error feedback that quotes the command, and replies such as `/msg` confirmations. To recognise them, a just-sent command's argument text is held in memory for 10 seconds; it is never written or logged.
- *Settings › Privacy* can hide server addresses on screen and has the delete-everything controls. Every individual record (session, server history, message, disconnect event) has its own Delete action with a confirmation.

## Export and backups

- **Export:** each section's **Export** button, or *Settings › Privacy/Storage*, writes JSON, CSV (RFC 4180, ISO 8601 UTC plus epoch-ms columns) or plain-text chat transcripts. The default destination is `config/sessioniq/exports/`; choose another folder with the native folder dialog or type a path.
- **Backups:** *Settings › Storage*. Backups are zip archives with a manifest listing each file's size and SHA-256 hash.
  - Automatic backups run on a schedule (default: daily, keep 10). Rotation only deletes older *automatic* backups.
  - A backup is verified before a restore is offered. **Replace** swaps your records for the backup's; **Merge** adds back only the sessions you no longer have.
  - Before any restore, a safety backup of the current data is made. The session being recorded is never touched.

## Building and testing

```
gradlew build                                   # compile, 34 unit tests, remapped JAR in build/libs/
gradlew runClientGameTest                       # full in-game test (integrated + dedicated servers, GUI)
gradlew runClientGameTest -PsiqPhase=crash      # records a session, then kills the JVM without shutdown
gradlew runClientGameTest -PsiqPhase=recover    # verifies recovery of that session, then every deletion path
gradlew prodClientGameTest                      # the release JAR in a production-mapped client
```

The game tests store data in `build/gametest-data/` and save screenshots to `build/run/clientGameTest/screenshots/`. Building requires a JDK 21; point `JAVA_HOME` at it before running Gradle.

## Known limitations

- **Ping** is the latency the server reports for you in the player list. Servers that hide it, or report 0 (for example on localhost), show as *unavailable* rather than 0 ms.
- **Realms** joins use their own connection flow, so failed Realms connection attempts are not recorded. Successful Realms visits are.
- **Server shutdowns:** whether the shutdown notice reaches the client before the socket closes depends on timing. Without it, the event is honestly shown as *connection lost (inferred)*.
- **Read timeouts** are classified from the client's own timeout signal. That path is unit-tested, but the automated in-game tests cannot provoke a real timeout.
- **Proxies:** a server switch inside a proxy (Velocity/BungeeCord) keeps the same connection, so it counts as one visit to the proxy's address.
- **Echo guard:** a server reply that happens to contain the argument of a command you typed in the last 10 seconds is not archived.
- **Chat copying:** copying works per message, or for a multi-selection. Selecting part of a message's text in the list is not supported.
- **One game at a time:** run one Minecraft instance per game directory at a time. A second instance leaves the first one's live session untouched instead of recovering it.
- **Durability:** atomic replacement and flushing depend on the filesystem. An interruption can damage at most the last line being appended, and that line is skipped when reading.
