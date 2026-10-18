import { Caller } from "./auth";
import { Body, HttpError, int, optStr } from "./http";
import { publicHost } from "./hosts";
import { friendsOf, notify } from "./live";

export const MAX_SERVERS_PER_PLAYER = 15;
const MAX_PLAYTIME_MS = 20 * 365 * 24 * 3600 * 1000;
const MAX_COUNT = 10_000_000;
const SNAPSHOT_MAX_AGE_MS = 35 * 60 * 1000;
/** After a stats upload, Global Statistics is recomputed if it is older than this. */
const SNAPSHOT_MIN_AGE_MS = 60 * 1000;
const ROWS_PER_STATEMENT = 20;

interface ServerTotals {
	playtime: number;
	joins: number;
}

function parseServers(body: Body): Map<string, ServerTotals> {
	const raw = body.servers;
	if (!Array.isArray(raw)) {
		throw new HttpError(400, "servers must be an array");
	}
	const out = new Map<string, ServerTotals>();
	for (const item of raw.slice(0, MAX_SERVERS_PER_PLAYER * 2)) {
		if (typeof item !== "object" || item === null) {
			continue;
		}
		const entry = item as Body;
		const host = typeof entry.host === "string" ? publicHost(entry.host) : null;
		if (!host) {
			continue;
		}
		const playtime = int(entry, "playtimeMs", MAX_PLAYTIME_MS);
		const joins = int(entry, "joins", MAX_COUNT);
		const prev = out.get(host);
		out.set(host, { playtime: (prev?.playtime ?? 0) + playtime, joins: (prev?.joins ?? 0) + joins });
	}
	return new Map([...out.entries()].sort((a, b) => b[1].playtime - a[1].playtime).slice(0, MAX_SERVERS_PER_PLAYER));
}

function chunks<T>(items: T[], size: number): T[][] {
	const out: T[][] = [];
	for (let i = 0; i < items.length; i += size) {
		out.push(items.slice(i, i + size));
	}
	return out;
}

/**
 * Statements that replace a player's per-server totals and apply only the difference to the
 * global server totals, so syncing the same numbers twice never double-counts.
 */
export async function replaceServers(db: D1Database, playerUuid: string, next: Map<string, ServerTotals>): Promise<D1PreparedStatement[]> {
	const { results } = await db
		.prepare("SELECT host, playtime_ms, joins FROM player_servers WHERE uuid = ?")
		.bind(playerUuid)
		.run<{ host: string; playtime_ms: number; joins: number }>();
	const old = new Map(results.map((r) => [r.host, { playtime: r.playtime_ms, joins: r.joins }]));
	const deltas: [string, number, number, number][] = [];
	const changed: [string, ServerTotals][] = [];
	const removed: string[] = [];
	for (const host of new Set([...old.keys(), ...next.keys()])) {
		const o = old.get(host);
		const n = next.get(host);
		const d: [string, number, number, number] = [host, (n?.playtime ?? 0) - (o?.playtime ?? 0), (n?.joins ?? 0) - (o?.joins ?? 0), (n ? 1 : 0) - (o ? 1 : 0)];
		if (d[1] !== 0 || d[2] !== 0 || d[3] !== 0) {
			deltas.push(d);
			if (n) {
				changed.push([host, n]);
			} else {
				removed.push(host);
			}
		}
	}
	if (deltas.length === 0) {
		return [];
	}
	// Only rows that changed are written; D1's free plan counts rows written per day.
	const statements: D1PreparedStatement[] = [];
	for (const part of chunks(removed, ROWS_PER_STATEMENT * 4)) {
		statements.push(db.prepare(`DELETE FROM player_servers WHERE uuid = ? AND host IN (${part.map(() => "?").join(", ")})`).bind(playerUuid, ...part));
	}
	for (const part of chunks(changed, ROWS_PER_STATEMENT)) {
		statements.push(
			db
				.prepare(
					`INSERT INTO player_servers (uuid, host, playtime_ms, joins) VALUES ${part.map(() => "(?, ?, ?, ?)").join(", ")}
					 ON CONFLICT (uuid, host) DO UPDATE SET playtime_ms = excluded.playtime_ms, joins = excluded.joins`,
				)
				.bind(...part.flatMap(([host, t]) => [playerUuid, host, t.playtime, t.joins])),
		);
	}
	for (const part of chunks(deltas, ROWS_PER_STATEMENT)) {
		statements.push(
			db
				.prepare(
					`INSERT INTO servers (host, playtime_ms, joins, players) VALUES ${part.map(() => "(?, ?, ?, ?)").join(", ")}
					 ON CONFLICT (host) DO UPDATE SET playtime_ms = playtime_ms + excluded.playtime_ms, joins = joins + excluded.joins, players = players + excluded.players`,
				)
				.bind(...part.flat()),
		);
	}
	if (removed.length > 0) {
		statements.push(db.prepare("DELETE FROM servers WHERE players <= 0"));
	}
	return statements;
}

/** Stores the caller's all-time totals. Totals mirror the player's local history. */
export async function sync(env: Env, ctx: ExecutionContext, caller: Caller, body: Body): Promise<{ ok: true }> {
	const player = await env.DB.prepare("SELECT share_stats FROM players WHERE uuid = ?").bind(caller.uuid).first<{ share_stats: number }>();
	if (!player) {
		throw new HttpError(401, "Sign in again");
	}
	const playtime = int(body, "playtimeMs", MAX_PLAYTIME_MS);
	const sessions = int(body, "sessions", MAX_COUNT);
	const totalJoins = int(body, "totalJoins", MAX_COUNT);
	const serversJoined = int(body, "serversJoined", MAX_COUNT);
	const topRaw = optStr(body, "topServer", 253);
	const topServer = topRaw ? publicHost(topRaw) : null;
	const topServerMs = topServer ? int(body, "topServerMs", MAX_PLAYTIME_MS) : 0;
	const servers = player.share_stats === 1 ? parseServers(body) : new Map<string, ServerTotals>();
	const now = Date.now();
	const statements = await replaceServers(env.DB, caller.uuid, servers);
	statements.push(
		env.DB.prepare(
			`UPDATE players SET playtime_ms = ?, sessions = ?, total_joins = ?, servers_joined = ?, top_server = ?, top_server_ms = ?, stats_at = ?, updated_at = ?
			 WHERE uuid = ?`,
		).bind(playtime, sessions, totalJoins, serversJoined, topServer, topServerMs, now, now, caller.uuid),
	);
	await env.DB.batch(statements);
	notify(env, ctx, await friendsOf(env.DB, caller.uuid), { t: "friends" });
	ctx.waitUntil(refreshIfStale(env.DB, SNAPSHOT_MIN_AGE_MS));
	return { ok: true };
}

/** Recomputes the snapshot unless it is younger than minAgeMs (bounds D1 reads under load). */
async function refreshIfStale(db: D1Database, minAgeMs: number): Promise<void> {
	const row = await db.prepare("SELECT at FROM snapshot WHERE id = 1").first<{ at: number }>();
	if (!row || Date.now() - row.at > minAgeMs) {
		await refreshSnapshot(db);
	}
}

export interface Snapshot {
	generatedAt: number;
	totals: { playtimeMs: number; players: number; servers: number; sessions: number };
	topServersByPlaytime: { host: string; playtimeMs: number; joins: number; players: number }[];
	topServersByJoins: { host: string; playtimeMs: number; joins: number; players: number }[];
	topPlayers: { name: string; playtimeMs: number; sessions: number; serversJoined: number; topServer: string | null }[];
}

export async function computeSnapshot(db: D1Database): Promise<Snapshot> {
	const [totals, serverCount, byPlaytime, byJoins, players] = await db.batch([
		db.prepare(
			"SELECT COUNT(*) AS players, COALESCE(SUM(playtime_ms), 0) AS playtime, COALESCE(SUM(sessions), 0) AS sessions FROM players WHERE share_stats = 1 AND stats_at IS NOT NULL",
		),
		db.prepare("SELECT COUNT(*) AS servers FROM servers"),
		db.prepare("SELECT host, playtime_ms, joins, players FROM servers ORDER BY playtime_ms DESC LIMIT 10"),
		db.prepare("SELECT host, playtime_ms, joins, players FROM servers ORDER BY joins DESC LIMIT 10"),
		db.prepare(
			"SELECT name, playtime_ms, sessions, servers_joined, top_server FROM players WHERE share_stats = 1 AND stats_at IS NOT NULL ORDER BY playtime_ms DESC LIMIT 10",
		),
	]);
	type ServerRow = { host: string; playtime_ms: number; joins: number; players: number };
	type PlayerRow = { name: string; playtime_ms: number; sessions: number; servers_joined: number; top_server: string | null };
	const t = totals.results[0] as { players: number; playtime: number; sessions: number };
	const server = (r: ServerRow) => ({ host: r.host, playtimeMs: r.playtime_ms, joins: r.joins, players: r.players });
	return {
		generatedAt: Date.now(),
		totals: { playtimeMs: t.playtime, players: t.players, servers: (serverCount.results[0] as { servers: number }).servers, sessions: t.sessions },
		topServersByPlaytime: (byPlaytime.results as ServerRow[]).map(server),
		topServersByJoins: (byJoins.results as ServerRow[]).map(server),
		topPlayers: (players.results as PlayerRow[]).map((r) => ({
			name: r.name,
			playtimeMs: r.playtime_ms,
			sessions: r.sessions,
			serversJoined: r.servers_joined,
			topServer: r.top_server ?? null,
		})),
	};
}

export async function refreshSnapshot(db: D1Database): Promise<Snapshot> {
	const snap = await computeSnapshot(db);
	await db
		.prepare("INSERT INTO snapshot (id, json, at) VALUES (1, ?, ?) ON CONFLICT (id) DO UPDATE SET json = excluded.json, at = excluded.at")
		.bind(JSON.stringify(snap), snap.generatedAt)
		.run();
	return snap;
}

/** The Global Statistics response: the cached snapshot plus, when signed in, the caller's rank. */
export async function global(env: Env, ctx: ExecutionContext, caller: Caller | null): Promise<Snapshot & { you: { rank: number; playtimeMs: number } | null }> {
	const row = await env.DB.prepare("SELECT json, at FROM snapshot WHERE id = 1").first<{ json: string; at: number }>();
	let snap: Snapshot;
	if (!row) {
		snap = await refreshSnapshot(env.DB);
	} else {
		snap = JSON.parse(row.json) as Snapshot;
		if (Date.now() - row.at > SNAPSHOT_MAX_AGE_MS) {
			ctx.waitUntil(refreshSnapshot(env.DB));
		}
	}
	let you: { rank: number; playtimeMs: number } | null = null;
	if (caller) {
		const me = await env.DB.prepare("SELECT playtime_ms, share_stats, stats_at FROM players WHERE uuid = ?")
			.bind(caller.uuid)
			.first<{ playtime_ms: number; share_stats: number; stats_at: number | null }>();
		if (me && me.share_stats === 1 && me.stats_at !== null) {
			const ahead = await env.DB.prepare("SELECT COUNT(*) AS n FROM players WHERE share_stats = 1 AND stats_at IS NOT NULL AND playtime_ms > ?")
				.bind(me.playtime_ms)
				.first<{ n: number }>();
			you = { rank: (ahead?.n ?? 0) + 1, playtimeMs: me.playtime_ms };
		}
	}
	return { ...snap, you };
}
