import { Caller } from "./auth";
import { Body, HttpError, bool, plain, str, uuid } from "./http";
import { publicHost } from "./hosts";
import { friendsOf, notify } from "./live";
import { replaceServers } from "./stats";

const ONLINE_WINDOW_MS = 5 * 60 * 1000;
// Below ONLINE_WINDOW_MS minus the client's 2-minute heartbeat, so an active player never looks offline.
const PRESENCE_REFRESH_MS = 3 * 60 * 1000;
const MAX_FRIENDS = 200;
const MAX_PENDING = 50;
const MAX_MESSAGE = 256;
const MESSAGES_PER_MINUTE = 20;

interface PlayerRow {
	uuid: string;
	name: string;
	last_seen: number | null;
	online: number;
	current_server: string | null;
	share_stats: number;
	show_online: number;
	show_server: number;
	show_stats: number;
	allow_chat: number;
	playtime_ms: number;
	sessions: number;
	servers_joined: number;
	total_joins: number;
	top_server: string | null;
	top_server_ms: number;
	stats_at: number | null;
}

async function me(env: Env, caller: Caller): Promise<PlayerRow> {
	const row = await env.DB.prepare("SELECT * FROM players WHERE uuid = ?").bind(caller.uuid).first<PlayerRow>();
	if (!row) {
		throw new HttpError(401, "Sign in again");
	}
	return row;
}

export async function profile(env: Env, caller: Caller) {
	const p = await me(env, caller);
	return {
		uuid: p.uuid,
		name: p.name,
		admin: caller.admin,
		privacy: privacyOf(p),
	};
}

function privacyOf(p: PlayerRow) {
	return {
		shareStats: p.share_stats === 1,
		showOnline: p.show_online === 1,
		showServer: p.show_server === 1,
		showStats: p.show_stats === 1,
		allowChat: p.allow_chat === 1,
	};
}

export async function setPrivacy(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const shareStats = bool(body, "shareStats");
	const flags = [shareStats, bool(body, "showOnline"), bool(body, "showServer"), bool(body, "showStats"), bool(body, "allowChat")].map((b) => (b ? 1 : 0));
	const statements = shareStats ? [] : await replaceServers(env.DB, caller.uuid, new Map());
	statements.push(
		env.DB.prepare("UPDATE players SET share_stats = ?, show_online = ?, show_server = ?, show_stats = ?, allow_chat = ?, updated_at = ? WHERE uuid = ?").bind(
			...flags,
			Date.now(),
			caller.uuid,
		),
	);
	await env.DB.batch(statements);
	notify(env, ctx, await friendsOf(env.DB, caller.uuid), { t: "friends" });
	return profile(env, caller);
}

/** Deletes everything stored about the signed-in player. */
export async function deleteMe(env: Env, ctx: ExecutionContext, caller: Caller) {
	const p = await me(env, caller);
	const u = caller.uuid;
	const friends = await friendsOf(env.DB, u);
	const statements = await replaceServers(env.DB, u, new Map());
	statements.push(
		env.DB.prepare("DELETE FROM player_servers WHERE uuid = ?").bind(u),
		env.DB.prepare("DELETE FROM friendships WHERE a = ? OR b = ?").bind(u, u),
		env.DB.prepare("DELETE FROM friend_requests WHERE from_uuid = ? OR to_uuid = ?").bind(u, u),
		env.DB.prepare("DELETE FROM blocks WHERE uuid = ? OR blocked = ?").bind(u, u),
		env.DB.prepare("DELETE FROM messages WHERE from_uuid = ? OR to_uuid = ?").bind(u, u),
		env.DB.prepare("UPDATE suggestions SET player = NULL WHERE player = ?").bind(p.name),
		env.DB.prepare("DELETE FROM players WHERE uuid = ?").bind(u),
	);
	await env.DB.batch(statements);
	notify(env, ctx, friends, { t: "friends" });
	return { ok: true };
}

/**
 * Heartbeat sent every 2 minutes while the game runs (every 10 minutes for players without
 * friends). The row is only rewritten when something changed or the last write is older than
 * three minutes, which keeps D1 writes low.
 */
export async function presence(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const online = bool(body, "online");
	const activity = online ? str(body, "activity", 1, 16) : "offline";
	let current: string | null = null;
	if (activity === "server") {
		const host = typeof body.server === "string" ? publicHost(body.server) : null;
		current = host ? `server:${host}` : "private";
	} else if (activity === "singleplayer" || activity === "private") {
		current = activity;
	}
	const now = Date.now();
	const before = await env.DB.prepare("SELECT online, current_server FROM players WHERE uuid = ?")
		.bind(caller.uuid)
		.first<{ online: number; current_server: string | null }>();
	await env.DB.prepare(
		`UPDATE players SET last_seen = ?, online = ?, current_server = ?
		 WHERE uuid = ? AND (online != ? OR IFNULL(current_server, '') != ? OR IFNULL(last_seen, 0) < ?)`,
	)
		.bind(now, online ? 1 : 0, current, caller.uuid, online ? 1 : 0, current ?? "", now - PRESENCE_REFRESH_MS)
		.run();
	if (before && (before.online !== (online ? 1 : 0) || (before.current_server ?? "") !== (current ?? ""))) {
		// Friends see the change right away instead of on their next reload.
		notify(env, ctx, await friendsOf(env.DB, caller.uuid), { t: "friends" });
	}
	const [unread, requests] = await env.DB.batch([
		env.DB.prepare("SELECT from_uuid, COUNT(*) AS n FROM messages WHERE to_uuid = ? AND read = 0 GROUP BY from_uuid").bind(caller.uuid),
		env.DB.prepare("SELECT COUNT(*) AS n FROM friend_requests WHERE to_uuid = ?").bind(caller.uuid),
	]);
	const counts: Record<string, number> = {};
	for (const r of unread.results as { from_uuid: string; n: number }[]) {
		counts[r.from_uuid] = r.n;
	}
	return { unread: counts, requests: (requests.results[0] as { n: number }).n };
}

function describeFriend(p: PlayerRow & { since: number }, mine: PlayerRow, unread: number) {
	const now = Date.now();
	const isOnline = p.online === 1 && (p.last_seen ?? 0) > now - ONLINE_WINDOW_MS;
	return {
		uuid: p.uuid,
		name: p.name,
		since: p.since,
		status: p.show_online === 1 ? (isOnline ? "online" : "offline") : "hidden",
		lastSeen: p.show_online === 1 ? p.last_seen : null,
		activity: p.show_online === 1 && p.show_server === 1 && isOnline ? p.current_server : null,
		stats:
			p.show_stats === 1 && p.stats_at !== null
				? {
						playtimeMs: p.playtime_ms,
						sessions: p.sessions,
						serversJoined: p.servers_joined,
						totalJoins: p.total_joins,
						topServer: p.top_server,
						topServerMs: p.top_server_ms,
						updatedAt: p.stats_at,
					}
				: null,
		canChat: p.allow_chat === 1 && mine.allow_chat === 1,
		theyAllowChat: p.allow_chat === 1,
		unread,
	};
}

export async function friends(env: Env, caller: Caller) {
	const mine = await me(env, caller);
	const [list, incoming, outgoing, blocked, unread] = await env.DB.batch([
		env.DB.prepare("SELECT p.*, f.since FROM friendships f JOIN players p ON p.uuid = f.b WHERE f.a = ? ORDER BY p.name_lower").bind(caller.uuid),
		env.DB.prepare("SELECT p.uuid, p.name, r.at FROM friend_requests r JOIN players p ON p.uuid = r.from_uuid WHERE r.to_uuid = ? ORDER BY r.at DESC").bind(caller.uuid),
		env.DB.prepare("SELECT p.uuid, p.name, r.at FROM friend_requests r JOIN players p ON p.uuid = r.to_uuid WHERE r.from_uuid = ? ORDER BY r.at DESC").bind(caller.uuid),
		env.DB.prepare("SELECT p.uuid, p.name, b.at FROM blocks b JOIN players p ON p.uuid = b.blocked WHERE b.uuid = ? ORDER BY b.at DESC").bind(caller.uuid),
		env.DB.prepare("SELECT from_uuid, COUNT(*) AS n FROM messages WHERE to_uuid = ? AND read = 0 GROUP BY from_uuid").bind(caller.uuid),
	]);
	const counts = new Map((unread.results as { from_uuid: string; n: number }[]).map((r) => [r.from_uuid, r.n]));
	return {
		friends: (list.results as (PlayerRow & { since: number })[]).map((p) => describeFriend(p, mine, counts.get(p.uuid) ?? 0)),
		incoming: incoming.results,
		outgoing: outgoing.results,
		blocked: blocked.results,
	};
}

async function isBlockedEitherWay(db: D1Database, a: string, b: string): Promise<{ byMe: boolean; byThem: boolean }> {
	const { results } = await db.prepare("SELECT uuid FROM blocks WHERE (uuid = ? AND blocked = ?) OR (uuid = ? AND blocked = ?)").bind(a, b, b, a).run<{ uuid: string }>();
	return { byMe: results.some((r) => r.uuid === a), byThem: results.some((r) => r.uuid === b) };
}

async function areFriends(db: D1Database, a: string, b: string): Promise<boolean> {
	return (await db.prepare("SELECT 1 AS x FROM friendships WHERE a = ? AND b = ?").bind(a, b).first()) !== null;
}

function befriend(db: D1Database, a: string, b: string, now: number): D1PreparedStatement[] {
	return [
		db.prepare("INSERT OR IGNORE INTO friendships (a, b, since) VALUES (?, ?, ?), (?, ?, ?)").bind(a, b, now, b, a, now),
		db.prepare("DELETE FROM friend_requests WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)").bind(a, b, b, a),
	];
}

export async function request(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const name = str(body, "name", 1, 16);
	const target = await env.DB.prepare("SELECT uuid, name FROM players WHERE name_lower = ? ORDER BY updated_at DESC LIMIT 1")
		.bind(name.toLowerCase())
		.first<{ uuid: string; name: string }>();
	if (!target) {
		throw new HttpError(404, `No SessionIQ user is named ${name}. They need to sign in to SessionIQ's online features first.`);
	}
	if (target.uuid === caller.uuid) {
		throw new HttpError(400, "You cannot add yourself");
	}
	if (await areFriends(env.DB, caller.uuid, target.uuid)) {
		throw new HttpError(409, `${target.name} is already your friend`);
	}
	const blocks = await isBlockedEitherWay(env.DB, caller.uuid, target.uuid);
	if (blocks.byMe) {
		throw new HttpError(409, `You blocked ${target.name}. Unblock them first.`);
	}
	if (blocks.byThem) {
		// Do not reveal the block: report success without creating a request.
		return { status: "sent", name: target.name };
	}
	const now = Date.now();
	const reverse = await env.DB.prepare("SELECT 1 AS x FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?").bind(target.uuid, caller.uuid).first();
	if (reverse) {
		await env.DB.batch(befriend(env.DB, caller.uuid, target.uuid, now));
		notify(env, ctx, [target.uuid], { t: "friends" });
		return { status: "friends", name: target.name };
	}
	const [pending, count] = await env.DB.batch([
		env.DB.prepare("SELECT COUNT(*) AS n FROM friend_requests WHERE from_uuid = ?").bind(caller.uuid),
		env.DB.prepare("SELECT COUNT(*) AS n FROM friendships WHERE a = ?").bind(caller.uuid),
	]);
	if ((pending.results[0] as { n: number }).n >= MAX_PENDING) {
		throw new HttpError(429, "You have too many pending friend requests");
	}
	if ((count.results[0] as { n: number }).n >= MAX_FRIENDS) {
		throw new HttpError(429, `You can have at most ${MAX_FRIENDS} friends`);
	}
	await env.DB.prepare("INSERT OR IGNORE INTO friend_requests (from_uuid, to_uuid, at) VALUES (?, ?, ?)").bind(caller.uuid, target.uuid, now).run();
	notify(env, ctx, [target.uuid], { t: "friends" });
	return { status: "sent", name: target.name };
}

export async function respond(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const other = uuid(body, "uuid");
	const accept = bool(body, "accept");
	const req = await env.DB.prepare("SELECT 1 AS x FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?").bind(other, caller.uuid).first();
	if (!req) {
		throw new HttpError(404, "That friend request no longer exists");
	}
	if (accept) {
		await env.DB.batch(befriend(env.DB, caller.uuid, other, Date.now()));
	} else {
		await env.DB.prepare("DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?").bind(other, caller.uuid).run();
	}
	notify(env, ctx, [other], { t: "friends" });
	return { ok: true };
}

export async function cancel(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const other = uuid(body, "uuid");
	await env.DB.prepare("DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?").bind(caller.uuid, other).run();
	notify(env, ctx, [other], { t: "friends" });
	return { ok: true };
}

export async function remove(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const other = uuid(body, "uuid");
	await env.DB.prepare("DELETE FROM friendships WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)").bind(caller.uuid, other, other, caller.uuid).run();
	notify(env, ctx, [other], { t: "friends" });
	return { ok: true };
}

export async function block(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const other = uuid(body, "uuid");
	if (other === caller.uuid) {
		throw new HttpError(400, "You cannot block yourself");
	}
	const u = caller.uuid;
	await env.DB.batch([
		env.DB.prepare("INSERT OR IGNORE INTO blocks (uuid, blocked, at) VALUES (?, ?, ?)").bind(u, other, Date.now()),
		env.DB.prepare("DELETE FROM friendships WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)").bind(u, other, other, u),
		env.DB.prepare("DELETE FROM friend_requests WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)").bind(u, other, other, u),
	]);
	// The blocked player only sees the friendship or request disappear, not why.
	notify(env, ctx, [other], { t: "friends" });
	return { ok: true };
}

export async function unblock(env: Env, caller: Caller, body: Body) {
	const other = uuid(body, "uuid");
	await env.DB.prepare("DELETE FROM blocks WHERE uuid = ? AND blocked = ?").bind(caller.uuid, other).run();
	return { ok: true };
}

export async function conversation(env: Env, caller: Caller, url: URL) {
	const other = (url.searchParams.get("with") ?? "").toLowerCase();
	const after = Number(url.searchParams.get("after") ?? "0");
	if (!/^[0-9a-f-]{36}$/.test(other) || !Number.isSafeInteger(after) || after < 0) {
		throw new HttpError(400, "Invalid conversation");
	}
	if (!(await areFriends(env.DB, caller.uuid, other))) {
		throw new HttpError(403, "You can only chat with friends");
	}
	const { results } = await env.DB.prepare(
		`SELECT id, from_uuid, text, at FROM messages
		 WHERE ((from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)) AND id > ?
		 ORDER BY id DESC LIMIT 100`,
	)
		.bind(caller.uuid, other, other, caller.uuid, after)
		.run<{ id: number; from_uuid: string; text: string; at: number }>();
	await env.DB.prepare("UPDATE messages SET read = 1 WHERE to_uuid = ? AND from_uuid = ? AND read = 0").bind(caller.uuid, other).run();
	return {
		messages: results.reverse().map((m) => ({ id: m.id, mine: m.from_uuid === caller.uuid, text: m.text, at: m.at })),
		retentionDays: Number(env.MESSAGE_RETENTION_DAYS),
	};
}

export async function send(env: Env, ctx: ExecutionContext, caller: Caller, body: Body) {
	const to = uuid(body, "to");
	const text = plain(str(body, "text", 1, MAX_MESSAGE * 2)).slice(0, MAX_MESSAGE);
	if (text.length === 0) {
		throw new HttpError(400, "Message is empty");
	}
	const mine = await me(env, caller);
	if (mine.allow_chat !== 1) {
		throw new HttpError(403, "Turn on friend chat in Settings › Online to send messages");
	}
	const [friendship, them, recent] = await env.DB.batch([
		env.DB.prepare("SELECT 1 AS x FROM friendships WHERE a = ? AND b = ?").bind(caller.uuid, to),
		env.DB.prepare("SELECT allow_chat FROM players WHERE uuid = ?").bind(to),
		env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE from_uuid = ? AND at > ?").bind(caller.uuid, Date.now() - 60_000),
	]);
	if (friendship.results.length === 0) {
		throw new HttpError(403, "You can only chat with friends");
	}
	if ((them.results[0] as { allow_chat: number } | undefined)?.allow_chat !== 1) {
		throw new HttpError(403, "This friend has chat turned off");
	}
	if ((recent.results[0] as { n: number }).n >= MESSAGES_PER_MINUTE) {
		throw new HttpError(429, "You are sending messages too quickly");
	}
	const now = Date.now();
	const row = await env.DB.prepare("INSERT INTO messages (from_uuid, to_uuid, text, at) VALUES (?, ?, ?, ?) RETURNING id")
		.bind(caller.uuid, to, text, now)
		.first<{ id: number }>();
	notify(env, ctx, [to], { t: "message", from: caller.uuid, name: mine.name, text: text.slice(0, 80) });
	return { id: row?.id ?? 0, mine: true, text, at: now };
}
