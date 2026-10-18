# Changelog

## 1.1.0 — Online features

SessionIQ can now go online, and only if you want it to. Your full history still stays on your computer. The new features share a small summary, and nothing is uploaded until you have read what is shared and pressed **Got it**.

### New

- **Global Statistics page.** See what all SessionIQ players have tracked together:
  - total playtime tracked
  - the most tracked servers
  - the servers with the most joins
  - the players with the most tracked playtime
  - your own rank

  Updates within about a minute, and your own new stats are uploaded when you open the page. Servers are counted under their main domain, so `londs.mcpvp.com` and `fra.mcpvp.com` are both `mcpvp.com`. Free hosts like Aternos and Minehut stay separate, since each subdomain there is a different server.
- **Friends page.** Add friends by their Minecraft username, accept or decline requests, and block players.
  - Requests, accepted requests, messages and friends' status changes show up instantly.
  - See whether friends are online and which public server they are playing on.
  - Open a friend to see their stats and chat with them.
  - New messages pop up with the sender's name and a preview, and friend requests pop up too, even while you play (can be turned off).
- **Suggestions page.** Send a feature idea or a bug report straight to the developer, up to 5 a day. Adding your username so the developer can reply is optional.
- **Settings › Online.** One switch turns every online feature off. Separate switches control:
  - whether you appear in Global Statistics
  - what friends can see: online status, current server, stats
  - whether friends can message you
  - notifications

  It also has **View what's sent**, which shows the exact data built from your history, and **Delete my online data**.
- **First-launch notice** that lists exactly what other players can see. It appears once when you start the game; nothing is uploaded until you accept it.

### What is shared

- **Visible to other SessionIQ users:**
  - your Minecraft username
  - total playtime tracked
  - number of sessions
  - servers joined and total server joins
  - your most-played public server and your playtime there
  - your leaderboard rank
- **Visible to friends you accept:** your online status, the public server you are on, and your stats. Each can be turned off.
- **Never uploaded:**
  - chat logs, disconnects, timelines and single-player world names
  - private or LAN server addresses (IP addresses, `localhost` and LAN names are dropped before anything is sent)
- **Launch counter:** launches are counted anonymously, with a random install ID plus the mod and Minecraft versions.
- **Friend messages** are kept on the server for 7 days so they can be delivered, then deleted.
- **Signing in** uses the Mojang-signed key that Minecraft already uses for secure chat, so it proves the account is yours without your Minecraft login. Your username is looked up by account ID on playerdb.co, a public Minecraft profile service.

### Changed

- The sidebar has a new group for Global, Friends and Suggestions. It shrinks to fit smaller windows.
- *Settings › Privacy* and the data folder's `README.txt` now describe the online features.
- A new `online.json` in the data folder holds the anonymous install ID and SessionIQ's sign-in token. It is not included in backups.

### Notes

- Accounts without a Microsoft login (offline-mode launchers) cannot sign in, but can still view Global Statistics and send suggestions. Friends need a signed-in account.
- If you never accept the notice, SessionIQ works like 1.0.0 and uploads nothing on its own. The only exception is a suggestion you choose to send.

### Open source

- The online service's full source is now in the repository under [`backend/`](backend/), next to the mod, under the same MIT license. You can check exactly what the server stores and does with it.
- It runs on Cloudflare Workers with a D1 database, and [`backend/README.md`](backend/README.md) explains how to run your own. Build the mod with `api_url` in `gradle.properties` pointing at your server. Leave `api_url` empty to build a version with no online features at all.

## 1.0.0

- First release: local session tracking, server history, chat archive, disconnect diagnostics, timeline, statistics, themes, export and backups.
