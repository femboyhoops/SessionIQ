// End-to-end check of the API against `npm run dev` (local D1, ALLOW_DEV_AUTH=1 in .dev.vars).
// Usage: npm run migrate:local && npm run dev   (in one terminal)
//        npm test                               (in another)
import assert from "node:assert/strict";
import { createPrivateKey, createSign, generateKeyPairSync, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

// Stand-in for Mojang's certificate key, created by `npm run setup-test-key`; the dev server
// trusts its public half (DEV_CERT_KEY in .dev.vars). Never committed.
const fakeMojang = createPrivateKey(readFileSync(new URL("./.dev-mojang-key.pem", import.meta.url)));

const BASE = process.env.API ?? "http://localhost:8787";

async function call(method, path, body, token) {
	const res = await fetch(BASE + path, {
		method,
		headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
		body: body === undefined ? undefined : JSON.stringify(body),
	});
	const data = await res.json();
	return { status: res.status, data };
}

async function ok(method, path, body, token) {
	const r = await call(method, path, body, token);
	assert.equal(r.status, 200, `${method} ${path} -> ${r.status} ${JSON.stringify(r.data)}`);
	return r.data;
}

/**
 * Builds the proof the game sends: a player key "issued" by the stand-in Mojang key over
 * (UUID, expiry, key), exactly as PlayerPublicKey.PublicKeyData#verifyKey lays it out, and a
 * signature over the sign-in message made with the player's private key.
 */
function proof(uuid, nonce, { issuer = fakeMojang, expiresAt = Date.now() + 3_600_000, message } = {}) {
	const player = generateKeyPairSync("rsa", { modulusLength: 2048 });
	const der = player.publicKey.export({ type: "spki", format: "der" });
	const payload = Buffer.alloc(24 + der.length);
	payload.write(uuid.replace(/-/g, ""), 0, "hex");
	payload.writeBigInt64BE(BigInt(expiresAt), 16);
	der.copy(payload, 24);
	const keySignature = createSign("SHA1").update(payload).sign(issuer);
	const signature = createSign("SHA256").update(message ?? `SessionIQ sign-in\n${uuid}\n${nonce}`).sign(player.privateKey);
	return { publicKey: der.toString("base64"), keyExpiresAt: expiresAt, keySignature: keySignature.toString("base64"), signature: signature.toString("base64") };
}

async function signIn(name, uuid = randomUUID()) {
	const { nonce } = await ok("POST", "/v1/auth/challenge", { uuid });
	assert.match(nonce, /^[0-9a-f]{40}$/);
	const v = await ok("POST", "/v1/auth/verify", { uuid, name, nonce, ...proof(uuid, nonce) });
	return { uuid, name, token: v.token };
}

async function rejected(label, mutate) {
	const uuid = randomUUID();
	const { nonce } = await ok("POST", "/v1/auth/challenge", { uuid });
	const body = mutate({ uuid, name: "Mallory", nonce, ...proof(uuid, nonce) }, uuid, nonce);
	const r = await call("POST", "/v1/auth/verify", body);
	assert.equal(r.status === 400 || r.status === 401, true, `${label}: expected rejection, got ${r.status} ${JSON.stringify(r.data)}`);
}

const run = Date.now().toString(36).slice(-5);
let passed = 0;
const step = (label) => console.log(`  ok ${++passed} - ${label}`);

assert.equal((await call("GET", "/v1/me")).status, 401);
assert.equal((await call("GET", "/v1/me", undefined, "v1.x.1.bad")).status, 401);
step("unauthenticated and forged tokens are rejected");

const bad = await call("POST", "/v1/auth/verify", { uuid: randomUUID(), name: "Nobody", nonce: "0".repeat(40) });
assert.equal(bad.status, 401);
step("a nonce that was not issued by the server is rejected");

const notMojang = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
await rejected("certificate not issued by Mojang", (b, uuid, nonce) => ({ ...b, ...proof(uuid, nonce, { issuer: notMojang }) }));
await rejected("certificate issued to another account", (b, uuid, nonce) => ({ ...b, ...proof(randomUUID(), nonce) }));
await rejected("expired certificate", (b, uuid, nonce) => ({ ...b, ...proof(uuid, nonce, { expiresAt: Date.now() - 1000 }) }));
await rejected("signature over a different message", (b, uuid, nonce) => ({ ...b, ...proof(uuid, nonce, { message: "something else" }) }));
await rejected("expiry changed after issuing", (b) => ({ ...b, keyExpiresAt: b.keyExpiresAt + 1 }));
await rejected("nonce for another account", (b) => ({ ...b, uuid: randomUUID() }));
step("forged, foreign, expired or tampered player certificates are rejected");

const alice = await signIn(`Alice${run}`);
const bob = await signIn(`Bob${run}`);
step("two players sign in");

const installId = randomUUID();
await ok("POST", "/v1/telemetry/launch", { installId, modVersion: "1.1.0", mcVersion: "1.21.11" });
await ok("POST", "/v1/telemetry/launch", { installId, modVersion: "1.1.0", mcVersion: "1.21.11" });
step("anonymous launches are counted");

const sync = (servers, playtimeMs) => ({
	playtimeMs,
	sessions: 12,
	totalJoins: servers.reduce((n, s) => n + s.joins, 0),
	serversJoined: servers.length,
	topServer: servers[0]?.host ?? null,
	topServerMs: servers[0]?.playtimeMs ?? 0,
	servers,
});
// mc.<host> and play.<host> are counted as <host>.
const host = `smoke${run}.net`;
await ok("POST", "/v1/me/sync", sync([
	{ host: `mc.${host}`, playtimeMs: 3_600_000, joins: 4 },
	{ host: "192.168.1.20:25565", playtimeMs: 999, joins: 1 },
	{ host: "localhost", playtimeMs: 999, joins: 1 },
], 5_000_000), alice.token);
await ok("POST", "/v1/me/sync", sync([{ host: `mc.${host}`, playtimeMs: 3_600_000, joins: 4 }], 5_000_000), alice.token);
await ok("POST", "/v1/me/sync", sync([{ host: `play.${host}`, playtimeMs: 1_000_000, joins: 2 }], 1_500_000), bob.token);
step("stats sync (private addresses are dropped; repeating a sync is idempotent)");

// The cron normally refreshes this; trigger it through the scheduled handler test route.
await fetch(`${BASE}/cdn-cgi/handler/scheduled`);
const g = await ok("GET", "/v1/global", undefined, alice.token);
const server = g.topServersByPlaytime.find((s) => s.host === host);
assert.ok(server, "the server appears under its cleaned-up name");
assert.equal(server.playtimeMs, 4_600_000);
assert.equal(server.joins, 6);
assert.equal(server.players, 2);
assert.ok(!g.topServersByPlaytime.some((s) => s.host.includes("192.168") || s.host === "localhost"));
assert.ok(g.topPlayers.some((p) => p.name === alice.name));
assert.ok(g.you && g.you.rank >= 1);
step("global statistics aggregate both players exactly once");

const req = await ok("POST", "/v1/friends/request", { name: bob.name.toLowerCase() }, alice.token);
assert.equal(req.status, "sent");
let bobFriends = await ok("GET", "/v1/friends", undefined, bob.token);
assert.equal(bobFriends.incoming.length, 1);
await ok("POST", "/v1/friends/respond", { uuid: alice.uuid, accept: true }, bob.token);
await ok("POST", "/v1/presence", { online: true, activity: "server", server: `mc.${host}:25565` }, bob.token);
let aliceFriends = await ok("GET", "/v1/friends", undefined, alice.token);
assert.equal(aliceFriends.friends.length, 1);
assert.equal(aliceFriends.friends[0].status, "online");
assert.equal(aliceFriends.friends[0].activity, `server:${host}`);
assert.equal(aliceFriends.friends[0].stats.playtimeMs, 1_500_000);
step("friend request, accept, presence and friend stats");

await ok("POST", "/v1/me/privacy", { shareStats: true, showOnline: true, showServer: false, showStats: false, allowChat: true }, bob.token);
aliceFriends = await ok("GET", "/v1/friends", undefined, alice.token);
assert.equal(aliceFriends.friends[0].activity, null);
assert.equal(aliceFriends.friends[0].stats, null);
step("privacy settings hide the server and stats from friends");

await ok("POST", "/v1/chat/send", { to: bob.uuid, text: "§khey\n§cthere§" }, alice.token);
const presence = await ok("POST", "/v1/presence", { online: true, activity: "menu" }, bob.token);
assert.equal(presence.unread[alice.uuid], 1);
const convo = await ok("GET", `/v1/chat?with=${alice.uuid}`, undefined, bob.token);
assert.equal(convo.messages.length, 1);
assert.equal(convo.messages[0].text, "hey there");
assert.equal(convo.messages[0].mine, false);
step("friend chat delivers plain one-line text (no § formatting codes) and tracks unread counts");

const stranger = await signIn(`Eve${run}`);
assert.equal((await call("POST", "/v1/chat/send", { to: bob.uuid, text: "hi" }, stranger.token)).status, 403);
assert.equal((await call("GET", `/v1/chat?with=${bob.uuid}`, undefined, stranger.token)).status, 403);
step("non-friends cannot chat or read conversations");

await ok("POST", "/v1/friends/block", { uuid: stranger.uuid }, bob.token);
const blockedReq = await ok("POST", "/v1/friends/request", { name: bob.name }, stranger.token);
assert.equal(blockedReq.status, "sent");
bobFriends = await ok("GET", "/v1/friends", undefined, bob.token);
assert.equal(bobFriends.incoming.length, 0);
assert.equal(bobFriends.blocked.length, 1);
step("blocked players cannot send requests (without learning they are blocked)");

const s = await ok("POST", "/v1/suggestions", { installId, category: "feature", text: "Please add a dark mode for the chart tooltips", modVersion: "1.1.0", includeName: true }, alice.token);
assert.ok(s.id > 0);
assert.equal((await call("GET", "/v1/admin/overview", undefined, alice.token)).status, 403);
step("suggestions are accepted; the admin API rejects non-admins");

await ok("POST", "/v1/me/delete", undefined, bob.token);
await fetch(`${BASE}/cdn-cgi/handler/scheduled`);
const after = await ok("GET", "/v1/global");
const s2 = after.topServersByPlaytime.find((x) => x.host === host);
assert.equal(s2.playtimeMs, 3_600_000);
assert.equal(s2.players, 1);
assert.equal((await ok("GET", "/v1/friends", undefined, alice.token)).friends.length, 0);
step("deleting an account removes its contribution, friendships and messages");

{
	const carol = await signIn(`Carol${run}`);
	const dave = await signIn(`Dave${run}`);
	const events = [];
	const ws = new WebSocket(BASE.replace(/^http/, "ws") + "/v1/live", { headers: { authorization: `Bearer ${dave.token}` } });
	ws.onmessage = (e) => events.push(e.data === "pong" ? { t: "pong" } : JSON.parse(e.data));
	const waitFor = (pred, label) =>
		new Promise((resolve, reject) => {
			const start = Date.now();
			const timer = setInterval(() => {
				const i = events.findIndex(pred);
				if (i >= 0) {
					clearInterval(timer);
					resolve(events.splice(i, 1)[0]);
				} else if (Date.now() - start > 5000) {
					clearInterval(timer);
					reject(new Error(`no live event: ${label} (got ${JSON.stringify(events)})`));
				}
			}, 25);
		});
	await waitFor((e) => e.t === "hello", "hello");
	ws.send("ping");
	await waitFor((e) => e.t === "pong", "pong");
	await ok("POST", "/v1/friends/request", { name: dave.name }, carol.token);
	await waitFor((e) => e.t === "friends", "friend request");
	await ok("POST", "/v1/friends/respond", { uuid: carol.uuid, accept: true }, dave.token);
	await ok("POST", "/v1/chat/send", { to: dave.uuid, text: "live hello" }, carol.token);
	const m = await waitFor((e) => e.t === "message", "message");
	assert.equal(m.from, carol.uuid);
	assert.equal(m.name, carol.name);
	assert.equal(m.text, "live hello");
	await ok("POST", "/v1/presence", { online: true, activity: "server", server: "londs.mcpvp.com" }, carol.token);
	await waitFor((e) => e.t === "friends", "friend joined a server");
	const seen = await ok("GET", "/v1/friends", undefined, dave.token);
	assert.equal(seen.friends[0].activity, "server:mcpvp.com", "sub-addresses are grouped under the main domain");
	ws.close();
	const noAuth = await new Promise((resolve) => {
		const anon = new WebSocket(BASE.replace(/^http/, "ws") + "/v1/live");
		anon.onopen = () => resolve("opened");
		anon.onerror = () => resolve("refused");
	});
	assert.equal(noAuth, "refused");
	step("live connection pushes requests, messages and friend status instantly (and needs a token)");
}

if (process.env.ADMIN_UUID) {
	// Start the dev server with `npx wrangler dev --var ADMIN_UUID:<uuid>` to run these.
	const admin = await signIn(`Admin${run}`, process.env.ADMIN_UUID);
	const overview = await ok("GET", "/v1/admin/overview", undefined, admin.token);
	assert.ok(overview.launches >= 2 && overview.installs >= 1 && overview.daily.length >= 1);
	const inbox = await ok("GET", "/v1/admin/suggestions?status=open", undefined, admin.token);
	const mine = inbox.suggestions.find((x) => x.id === s.id);
	assert.equal(mine.player, alice.name);
	await ok("POST", "/v1/admin/suggestions/update", { id: s.id, status: "done" }, admin.token);
	const done = await ok("GET", "/v1/admin/suggestions?status=done", undefined, admin.token);
	assert.ok(done.suggestions.some((x) => x.id === s.id));
	await ok("POST", "/v1/admin/suggestions/update", { id: s.id, status: "delete" }, admin.token);
	step("admin overview and suggestion inbox");

	// Live "online now" count, pushed to the admin as players connect and disconnect.
	const counts = [];
	const connect = (token, onEvent) =>
		new Promise((resolve, reject) => {
			const ws = new WebSocket(BASE.replace(/^http/, "ws") + "/v1/live", { headers: { authorization: `Bearer ${token}` } });
			ws.onmessage = (e) => e.data !== "pong" && onEvent?.(JSON.parse(e.data));
			ws.onopen = () => resolve(ws);
			ws.onerror = reject;
		});
	const nextCount = (label) =>
		new Promise((resolve, reject) => {
			const start = Date.now();
			const timer = setInterval(() => {
				if (counts.length > 0) {
					clearInterval(timer);
					resolve(counts.shift());
				} else if (Date.now() - start > 5000) {
					clearInterval(timer);
					reject(new Error(`no online count pushed: ${label}`));
				}
			}, 25);
		});
	const adminWs = await connect(admin.token, (e) => e.t === "online" && counts.push(e.count));
	const base = await nextCount("admin connected");
	assert.ok(base >= 1, "the admin counts as online");
	const visitor = await signIn(`Visitor${run}`);
	const visitorWs = await connect(visitor.token);
	assert.equal(await nextCount("player connected"), base + 1);
	assert.equal((await ok("GET", "/v1/admin/overview", undefined, admin.token)).onlineNow, base + 1);
	visitorWs.close();
	assert.equal(await nextCount("player disconnected"), base);
	adminWs.close();
	step("online-now count is live: pushed to the admin on every connect and disconnect");
}

await ok("POST", "/v1/telemetry/forget", { installId });
step("install telemetry can be forgotten");

console.log(`\n${passed} checks passed`);
