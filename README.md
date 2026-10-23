# SessionIQ

Client-side session analytics and history for **Minecraft Java Edition 1.21.11 (Fabric)**. SessionIQ records your play sessions, server visits, received chat, disconnects, ping and memory, and keeps all of it on your computer in plain JSON files. Optional online features add Global Statistics, Friends and Suggestions.

- **Minecraft** 1.21.11 · **Fabric Loader** ≥ 0.17 (built against 0.19.5) · **Fabric API** 0.141.6+1.21.11 (any 1.21.11 build) · **Java** 21
- Client-only; works on any server without a server-side mod. Your history never leaves your computer. The online features share only the summary described under [Online features](#online-features), and can be turned off completely.

## Install

1. Install Fabric Loader for 1.21.11 and Fabric API.
2. Copy `sessioniq-1.1.1.jar` into `.minecraft/mods/`.
3. Start the game. Recording begins automatically. On the first launch, SessionIQ shows what its online features share; nothing is uploaded until you press **Got it**.

## Using it

- Press **U** to open the dashboard. Change the key in *Settings › General › Open SessionIQ* (press **Change**, then the new key), or in *Options › Controls › Key Binds › SessionIQ*. The settings page warns about conflicting bindings.
- **Sections:** Overview, Sessions, Servers, Chat History, Disconnects, Timeline, Statistics, Global, Friends, Suggestions, Settings. Detail pages (a session, a server, a message, a disconnect event, a day on the timeline) open on top. **Esc** or the back arrow returns, and filters are kept.
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
| `online.json` | Anonymous install ID and SessionIQ's own sign-in token for the online features (not included in backups) |

With online features in use, SessionIQ keeps one file outside the game folder: `<user folder>/.sessioniq/device.json`. It holds a random device ID shared by your Minecraft instances on this computer, so your accounts count as one player in Global Statistics. *Delete my online data* removes it.
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

## Online features

SessionIQ 1.1 adds optional online features, backed by a small service the SessionIQ developer runs on Cloudflare (source in [`backend/`](backend/)). Nothing is uploaded until you accept the notice shown at first launch. You can turn everything off, or delete your shared data, in *Settings › Online*.

- **Global Statistics:** total playtime tracked by all SessionIQ players, the most tracked servers, the servers with the most joins, and the players with the most tracked playtime.
- **Friends:** add players by Minecraft username. See whether they are online, which public server they are on and their stats, and chat with them. Requests, messages and status changes appear instantly, and new messages pop up with a preview. Each of these can be turned off in *Settings › Online*, and players can be blocked.
- **Suggestions:** send an idea or a bug report straight to the developer.

**Visible to other SessionIQ users:**

- Your Minecraft username.
- Total playtime tracked, number of sessions, number of servers joined and total server joins.
- Your most-played server and your playtime there (public servers only).
- Your leaderboard rank.

**Friends can also see** your online status, the public server you are on and your stats, and can chat with you. Each of these is a separate setting.

**Never uploaded:**

- Chat logs, disconnects, timelines and single-player world names.
- Private or LAN server addresses. IP addresses, `localhost` and LAN names are dropped before anything is sent, and the service rejects them too.

**Also sent:**

- **Launch counter:** each launch is counted anonymously, with a random install ID plus the mod and Minecraft versions.
- **Device ID:** a random ID shared by your Minecraft instances on this computer, sent with your stats. Accounts with the same device ID count as one player, and only the account you are currently using is listed in Global Statistics.
- **Friend messages** are stored on the service for 7 days so they can be delivered, then deleted.

**Signing in** uses the Mojang-signed key that Minecraft already has for secure chat. The game signs a one-time challenge with it, and the service checks Mojang's signature on the key. Your Minecraft login token is never read or sent. The service looks up your current username by account ID on playerdb.co. Accounts without a Microsoft login (offline-mode launchers) cannot sign in, but can still view Global Statistics and send suggestions.

*Settings › Online › View what's sent* shows the exact data built from your current history.

## Export and backups

- **Export:** each section's **Export** button, or *Settings › Privacy/Storage*, writes JSON, CSV (RFC 4180, ISO 8601 UTC plus epoch-ms columns) or plain-text chat transcripts. The default destination is `config/sessioniq/exports/`; choose another folder with the native folder dialog or type a path.
- **Backups:** *Settings › Storage*. Backups are zip archives with a manifest listing each file's size and SHA-256 hash.
  - Automatic backups run on a schedule (default: daily, keep 10). Rotation only deletes older *automatic* backups.
  - A backup is verified before a restore is offered. **Replace** swaps your records for the backup's; **Merge** adds back only the sessions you no longer have.
  - Before any restore, a safety backup of the current data is made. The session being recorded is never touched.

## Building and testing

```
gradlew build                                   # compile, 40 unit tests, remapped JAR in build/libs/
gradlew runClientGameTest                       # full in-game test (integrated + dedicated servers, GUI)
gradlew runClientGameTest -PsiqPhase=crash      # records a session, then kills the JVM without shutdown
gradlew runClientGameTest -PsiqPhase=recover    # verifies recovery of that session, then every deletion path
gradlew runClientGameTest -PsiqPhase=online     # online features against a local backend (see backend/README.md)
gradlew prodClientGameTest                      # the release JAR in a production-mapped client
```

The game tests store data in `build/gametest-data/` and save screenshots to `build/run/clientGameTest/screenshots/`. Apart from the online phase, they run with online features off. Building requires a JDK 21; point `JAVA_HOME` at it before running Gradle.

The online service URL is built into the JAR from `api_url` in `gradle.properties`. When it is empty, the build has no online features. For testing, `-Dsessioniq.api=<url>` overrides the URL, and `-Dsessioniq.offline=true` turns online features off. The backend lives in [`backend/`](backend/README.md).

## Known limitations

- **Ping** is the latency the server reports for you in the player list. Servers that hide it, or report 0 (for example on localhost), show as *unavailable* rather than 0 ms.
- **Realms** joins use their own connection flow, so failed Realms connection attempts are not recorded. Successful Realms visits are.
- **Server shutdowns:** whether the shutdown notice reaches the client before the socket closes depends on timing. Without it, the event is honestly shown as *connection lost (inferred)*.
- **Read timeouts** are classified from the client's own timeout signal. That path is unit-tested, but the automated in-game tests cannot provoke a real timeout.
- **Proxies:** a server switch inside a proxy (Velocity/BungeeCord) keeps the same connection, so it counts as one visit to the proxy's address.
- **Echo guard:** a server reply that happens to contain the argument of a command you typed in the last 10 seconds is not archived.
- **Chat copying:** copying works per message, or for a multi-selection. Selecting part of a message's text in the list is not supported.
- **One game at a time:** run one Minecraft instance per game directory at a time. A second instance leaves the first one's live session untouched instead of recovering it.
- **Instant updates** use a live connection to the online service. If a network blocks it, friends, messages and status fall back to refreshing every 15 seconds (open pages) or every 2–10 minutes (pop-ups).
- **Server names:** online features count a server under its main domain (`londs.mcpvp.com` and `fra.mcpvp.com` are both `mcpvp.com`). Free hosts where every subdomain is a different server (Aternos, Minehut, exaroton and similar) are kept apart. A network reached through two different domains still counts as two servers.
- **Durability:** atomic replacement and flushing depend on the filesystem. An interruption can damage at most the last line being appended, and that line is skipped when reading.
