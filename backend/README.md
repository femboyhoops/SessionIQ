# SessionIQ online service

The backend for SessionIQ's optional online features: Global Statistics, Friends (status, current server, stats, chat), Suggestions, and the owner-only Admin page. It is a Cloudflare Worker with a D1 (SQLite) database and runs on Cloudflare's free plan.

## What it stores

| Table | Contents | Grows with |
|---|---|---|
| `installs`, `daily` | Random install ID, launch count, first/last seen, mod and Minecraft version; one row per UTC day | installs / days |
| `players` | Minecraft UUID and name, privacy settings, all-time totals, online status and current public server | players |
| `players.device_id`, `players.superseded` | A random device ID shared by a player's Minecraft instances. Accounts with the same ID count as one player: only the one that uploaded most recently is listed, and only its servers count toward the totals | (columns) |
| `player_servers`, `servers` | Playtime and joins per **public** hostname (at most 15 per player), and their running totals | players × servers |
| `friendships`, `friend_requests`, `blocks` | Who is friends with whom | friendships |
| `messages` | Friend chat, deleted after `MESSAGE_RETENTION_DAYS` (7) by the cron job | messages per week |
| `suggestions` | Category, text, mod version, optional username | suggestions |
| `snapshot` | The precomputed Global Statistics response | constant |
| `live_sessions` | Players with an open live connection right now, for the Admin page's live "Online now" count | players online |

A **`LiveHub` Durable Object** (one per signed-in player, `src/live.ts`) holds the game's live WebSocket connection. When something a player should see changes, the Worker pushes a tiny event, and the game reloads that data through the normal API:

- **Changes that trigger an event:** a friend request, an accept, a message, a friend's status or stats, a privacy change.
- **What it stores:** nothing besides the open connection.
- **Cost:** it uses the hibernation API, so idle connections cost nothing. Keep-alive pings (every 40 s) are answered without waking it.

Server names are grouped under their registrable domain (`src/hosts.ts`, kept in sync with the mod's `PublicHost.java`), so `londs.mcpvp.com` counts as `mcpvp.com`. Free hosting domains where each subdomain is a different server (Aternos, Minehut, exaroton and similar) are listed there and kept apart. Existing rows regroup automatically as players sync again.

Everything is a running total, so the database grows with the number of players and servers, not with playtime. Private addresses (IPs, `localhost`, LAN names) are rejected on both the client and the server. Invocation logs are turned off in `wrangler.jsonc`, so request metadata such as IP addresses is not stored.

### Sign-in

Mojang blocks its own APIs (including the `hasJoined` check servers normally use) from Cloudflare's network, so sign-in does not call Mojang at all:

1. The Worker issues a one-time nonce, bound to the player's UUID and valid for 2 minutes.
2. The game signs it with the private half of the **player key** that Mojang issues every account for secure chat. It sends the public key, its expiry, and Mojang's signature over (UUID, expiry, key).
3. The Worker checks Mojang's signature with Mojang's public certificate keys, bundled in `src/mojang-keys.ts`. This is the same check a Minecraft server does on chat keys. It then checks the nonce signature with the player key.
4. The current username for the UUID comes from [playerdb.co](https://playerdb.co), since claimed names are not trusted.

The player's Minecraft login token is never read or sent. If Mojang ever rotates its certificate keys (sign-in then fails with "not issued by Mojang"), run `npm run update-keys` on your own computer and deploy again.

## Free-plan budget

The Workers Free plan allows 100,000 requests a day. D1 on the free plan allows 5 GB of storage, 5 million rows read and 100,000 rows written per day. The client keeps its traffic low:

- **Heartbeat:** every 2 minutes for players with friends, every 10 minutes for players without. The database row is only rewritten when something changed, or at most every 3 minutes.
- **Stats sync:** at most every 30 minutes and after leaving a server, and only when the totals changed. Only the changed rows are written.
- **Global Statistics:** reads one precomputed row. It is recomputed after a stats upload when older than a minute, and by the cron every 30 minutes. Recomputing reads every player row once, so this is at most about 1,440 × players rows read a day, well inside the 5 million at 1,000 players.
- **Live connections (Durable Objects):** the Workers Free plan includes 100,000 Durable Object requests a day. Each connect and each event costs one request, and keep-alive pings count as 1/20 of a request each. 1,000 players for 3 hours is roughly 15,000–30,000 a day.
- **Chat:** polled every 4 seconds, but only while a conversation is open.

A rough estimate for 1,000 players a day, each playing 3 hours (30% with friends):

- About 55,000 requests a day.
- About 65,000 rows written a day.

So the free plan covers roughly up to that size. The row writes run out first; check *Workers & Pages › D1 › Metrics* in the Cloudflare dashboard. Beyond that, Workers Paid ($5/month) raises every limit by a large margin. Storage is not a concern: even 50,000 players come to a few tens of megabytes.

## Deploy

Requires Node.js 18+ and a free Cloudflare account.

```sh
cd backend
npm install
npx wrangler login
npx wrangler d1 create sessioniq          # copy the printed database_id into wrangler.jsonc
npm run migrate:remote                    # creates the tables
npx wrangler secret put TOKEN_SECRET      # paste a long random string (e.g. from a password manager)
npx wrangler secret put DISCORD_WEBHOOK   # optional: forward suggestions to a Discord channel
npm run deploy                            # prints https://sessioniq-api.<your-subdomain>.workers.dev
```

Then:

1. Set `ADMIN_UUID` in `wrangler.jsonc` to your Minecraft UUID (with dashes; look it up on any Minecraft profile site) and run `npm run deploy` again. Your account then sees the **Admin** page in the mod.
2. Put the Worker URL in the mod's `gradle.properties` as `api_url=...` and rebuild the mod.

Check that it is up: `https://sessioniq-api.<your-subdomain>.workers.dev/v1/health` returns `{"ok":true}`.

Schema changes go in a new file in `migrations/` (for example `0002_*.sql`), applied with `npm run migrate:remote`.

## Develop and test locally

```sh
cp .dev.vars.example .dev.vars            # local secrets; ALLOW_DEV_AUTH and DEV_CERT_KEY only apply to localhost
npm run setup-test-key                    # creates a throwaway test certificate key (git-ignored)
npm run migrate:local
npm run dev -- --test-scheduled           # http://127.0.0.1:8787
API=http://127.0.0.1:8787 npm test        # end-to-end API checks (in a second terminal)
npm run check                             # type check
```

The mod's in-game online test (`gradlew runClientGameTest -PsiqPhase=online`) runs against this local server. Start it with `npm run dev -- --test-scheduled --ip 127.0.0.1 --var ADMIN_UUID:5e551017-0000-4000-8000-000000000001`.

## Useful queries

```sh
npx wrangler d1 execute sessioniq --remote --command "SELECT COUNT(*) FROM installs"
npx wrangler d1 execute sessioniq --remote --command "SELECT id, category, text FROM suggestions WHERE status = 'new'"
```

## API

All routes are JSON. **Auth** routes need `Authorization: Bearer <token>` from `/v1/auth/verify`.

| Route | Auth | Purpose |
|---|---|---|
| `POST /v1/auth/challenge`, `POST /v1/auth/verify` | | Sign in with the Mojang-signed player key |
| `POST /v1/telemetry/launch`, `POST /v1/telemetry/forget` | | Anonymous launch counter |
| `GET /v1/global` | optional | Global Statistics (+ your rank when signed in) |
| `POST /v1/suggestions` | optional | Send a suggestion (5 per install per day) |
| `GET /v1/me`, `POST /v1/me/sync`, `POST /v1/me/privacy`, `POST /v1/me/delete` | ✓ | Profile, stats upload, privacy settings, delete all data |
| `POST /v1/presence` | ✓ | Heartbeat; returns unread messages and pending requests |
| `GET /v1/live` (WebSocket) | ✓ | Live events: `{"t":"friends"}` (reload friends) and `{"t":"message","from","name","text"}` |
| `GET /v1/friends`, `POST /v1/friends/{request,respond,cancel,remove,block,unblock}` | ✓ | Friends |
| `GET /v1/chat?with=<uuid>&after=<id>`, `POST /v1/chat/send` | ✓ | Friend chat |
| `GET /v1/admin/overview`, `GET /v1/admin/suggestions`, `POST /v1/admin/suggestions/update` | admin | Owner statistics and suggestions inbox |
