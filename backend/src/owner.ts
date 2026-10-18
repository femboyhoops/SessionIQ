import { Caller } from "./auth";
import { Body, HttpError, bool, plain, str, utcDay, uuid } from "./http";
import { onlineNowQuery } from "./live";

const SUGGESTIONS_PER_DAY = 5;
const CATEGORIES = ["feature", "bug", "other"];
const STATUSES = ["new", "read", "done"];
const DAY_MS = 24 * 3600 * 1000;

/** Anonymous launch counter: a random install id, the mod version and the Minecraft version. */
export async function launch(env: Env, body: Body) {
	const id = uuid(body, "installId");
	const modVersion = str(body, "modVersion", 1, 32);
	const mcVersion = str(body, "mcVersion", 1, 32);
	const now = Date.now();
	const day = utcDay(now);
	const existing = await env.DB.prepare("SELECT last_seen FROM installs WHERE id = ?").bind(id).first<{ last_seen: number }>();
	const firstToday = existing === null || utcDay(existing.last_seen) !== day ? 1 : 0;
	await env.DB.batch([
		env.DB.prepare(
			`INSERT INTO installs (id, first_seen, last_seen, launches, mod_version, mc_version) VALUES (?, ?, ?, 1, ?, ?)
			 ON CONFLICT (id) DO UPDATE SET last_seen = excluded.last_seen, launches = launches + 1, mod_version = excluded.mod_version, mc_version = excluded.mc_version`,
		).bind(id, now, now, modVersion, mcVersion),
		env.DB.prepare(
			`INSERT INTO daily (day, launches, active, new_installs) VALUES (?, 1, ?, ?)
			 ON CONFLICT (day) DO UPDATE SET launches = launches + 1, active = active + excluded.active, new_installs = new_installs + excluded.new_installs`,
		).bind(day, firstToday, existing === null ? 1 : 0),
	]);
	return { ok: true };
}

export async function forget(env: Env, body: Body) {
	const id = uuid(body, "installId");
	await env.DB.batch([
		env.DB.prepare("DELETE FROM installs WHERE id = ?").bind(id),
		env.DB.prepare("UPDATE suggestions SET install_id = '' WHERE install_id = ?").bind(id),
	]);
	return { ok: true };
}

export async function suggest(env: Env, ctx: ExecutionContext, body: Body, playerName: string | null) {
	const installId = uuid(body, "installId");
	const category = str(body, "category", 1, 16);
	if (!CATEGORIES.includes(category)) {
		throw new HttpError(400, "Unknown category");
	}
	const text = plain(str(body, "text", 5, 2000)).slice(0, 1000);
	const modVersion = str(body, "modVersion", 1, 32);
	const includeName = bool(body, "includeName");
	const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM suggestions WHERE install_id = ? AND at > ?")
		.bind(installId, Date.now() - DAY_MS)
		.first<{ n: number }>();
	if ((recent?.n ?? 0) >= SUGGESTIONS_PER_DAY) {
		throw new HttpError(429, `You can send ${SUGGESTIONS_PER_DAY} suggestions a day. Thank you for the feedback!`);
	}
	const player = includeName ? playerName : null;
	const row = await env.DB.prepare("INSERT INTO suggestions (at, install_id, player, category, text, mod_version) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
		.bind(Date.now(), installId, player, category, text, modVersion)
		.first<{ id: number }>();
	if (env.DISCORD_WEBHOOK) {
		ctx.waitUntil(forwardToDiscord(env.DISCORD_WEBHOOK, row?.id ?? 0, category, text, player, modVersion));
	}
	return { ok: true, id: row?.id ?? 0 };
}

async function forwardToDiscord(webhook: string, id: number, category: string, text: string, player: string | null, modVersion: string): Promise<void> {
	try {
		const res = await fetch(webhook, {
			method: "POST",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({
				username: "SessionIQ suggestions",
				allowed_mentions: { parse: [] },
				embeds: [{ title: `#${id} · ${category}`, description: text, footer: { text: `${player ?? "anonymous"} · SessionIQ ${modVersion}` } }],
			}),
		});
		if (!res.ok) {
			console.error(JSON.stringify({ msg: "discord webhook failed", status: res.status }));
		}
	} catch (e) {
		console.error(JSON.stringify({ msg: "discord webhook error", error: String(e) }));
	}
}

function requireAdmin(caller: Caller): void {
	if (!caller.admin) {
		throw new HttpError(403, "Admin only");
	}
}

export async function adminOverview(env: Env, caller: Caller) {
	requireAdmin(caller);
	const now = Date.now();
	const [installs, a1, a7, a30, players, players7, newSuggestions, daily, versions, onlineNow] = await env.DB.batch([
		env.DB.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(launches), 0) AS launches FROM installs"),
		env.DB.prepare("SELECT COUNT(*) AS n FROM installs WHERE last_seen > ?").bind(now - DAY_MS),
		env.DB.prepare("SELECT COUNT(*) AS n FROM installs WHERE last_seen > ?").bind(now - 7 * DAY_MS),
		env.DB.prepare("SELECT COUNT(*) AS n FROM installs WHERE last_seen > ?").bind(now - 30 * DAY_MS),
		env.DB.prepare("SELECT COUNT(*) AS n FROM players"),
		env.DB.prepare("SELECT COUNT(*) AS n FROM players WHERE last_seen > ?").bind(now - 7 * DAY_MS),
		env.DB.prepare("SELECT COUNT(*) AS n FROM suggestions WHERE status = 'new'"),
		env.DB.prepare("SELECT day, launches, active, new_installs FROM daily ORDER BY day DESC LIMIT 30"),
		env.DB.prepare("SELECT mod_version, COUNT(*) AS n FROM installs WHERE last_seen > ? GROUP BY mod_version ORDER BY n DESC LIMIT 10").bind(now - 30 * DAY_MS),
		onlineNowQuery(env.DB),
	]);
	const n = (r: D1Result) => (r.results[0] as { n: number }).n;
	const inst = installs.results[0] as { n: number; launches: number };
	return {
		onlineNow: n(onlineNow),
		installs: inst.n,
		launches: inst.launches,
		active1d: n(a1),
		active7d: n(a7),
		active30d: n(a30),
		players: n(players),
		playersActive7d: n(players7),
		newSuggestions: n(newSuggestions),
		daily: (daily.results as { day: string; launches: number; active: number; new_installs: number }[])
			.reverse()
			.map((d) => ({ day: d.day, launches: d.launches, active: d.active, newInstalls: d.new_installs })),
		versions: (versions.results as { mod_version: string | null; n: number }[]).map((v) => ({ version: v.mod_version ?? "unknown", installs: v.n })),
	};
}

export async function adminSuggestions(env: Env, caller: Caller, url: URL) {
	requireAdmin(caller);
	const status = url.searchParams.get("status") ?? "open";
	const before = Number(url.searchParams.get("before") ?? "0") || Number.MAX_SAFE_INTEGER;
	const where = status === "all" ? "1 = 1" : status === "open" ? "status IN ('new', 'read')" : "status = ?";
	const stmt = env.DB.prepare(
		`SELECT id, at, player, category, text, mod_version, status FROM suggestions WHERE ${where} AND id < ? ORDER BY id DESC LIMIT 50`,
	);
	const { results } = await (STATUSES.includes(status) ? stmt.bind(status, before) : stmt.bind(before)).run<{
		id: number;
		at: number;
		player: string | null;
		category: string;
		text: string;
		mod_version: string | null;
		status: string;
	}>();
	return {
		suggestions: results.map((s) => ({ id: s.id, at: s.at, player: s.player, category: s.category, text: s.text, modVersion: s.mod_version, status: s.status })),
	};
}

export async function adminUpdateSuggestion(env: Env, caller: Caller, body: Body) {
	requireAdmin(caller);
	const id = body.id;
	const status = str(body, "status", 1, 16);
	if (typeof id !== "number" || !Number.isSafeInteger(id)) {
		throw new HttpError(400, "id must be a number");
	}
	if (status === "delete") {
		await env.DB.prepare("DELETE FROM suggestions WHERE id = ?").bind(id).run();
	} else if (STATUSES.includes(status)) {
		await env.DB.prepare("UPDATE suggestions SET status = ? WHERE id = ?").bind(status, id).run();
	} else {
		throw new HttpError(400, "Unknown status");
	}
	return { ok: true };
}
