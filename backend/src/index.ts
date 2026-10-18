import { Caller, authenticate, challenge, verify } from "./auth";
import { HttpError, fail, json, readBody } from "./http";
import { adminOverview, adminSuggestions, adminUpdateSuggestion, forget, launch, suggest } from "./owner";
import { block, cancel, conversation, deleteMe, friends, presence, profile, remove, request, respond, send, setPrivacy, unblock } from "./social";
import { global, refreshSnapshot, sync } from "./stats";

export { LiveHub } from "./live";

type Handler = (env: Env, ctx: ExecutionContext, request: Request, url: URL) => Promise<unknown>;
type Authed = (env: Env, ctx: ExecutionContext, caller: Caller, request: Request, url: URL) => Promise<unknown>;

const authed =
	(handler: Authed): Handler =>
	async (env, ctx, request, url) =>
		handler(env, ctx, await authenticate(env, request), request, url);

async function optionalCaller(env: Env, request: Request): Promise<Caller | null> {
	if (!request.headers.has("authorization")) {
		return null;
	}
	try {
		return await authenticate(env, request);
	} catch {
		return null;
	}
}

const routes: Record<string, Handler> = {
	"GET /v1/health": async () => ({ ok: true }),

	"POST /v1/auth/challenge": async (env, _ctx, req) => challenge(env, await readBody(req)),
	"POST /v1/auth/verify": async (env, _ctx, req, url) => {
		const result = await verify(env, await readBody(req), url);
		const now = Date.now();
		await env.DB.prepare(
			`INSERT INTO players (uuid, name, name_lower, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
			 ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, updated_at = excluded.updated_at`,
		)
			.bind(result.uuid, result.name, result.name.toLowerCase(), now, now)
			.run();
		return result;
	},

	"POST /v1/telemetry/launch": async (env, _ctx, req) => launch(env, await readBody(req)),
	"POST /v1/telemetry/forget": async (env, _ctx, req) => forget(env, await readBody(req)),

	"POST /v1/suggestions": async (env, ctx, req) => {
		const caller = await optionalCaller(env, req);
		const body = await readBody(req);
		let name: string | null = null;
		if (caller) {
			name = (await env.DB.prepare("SELECT name FROM players WHERE uuid = ?").bind(caller.uuid).first<{ name: string }>())?.name ?? null;
		}
		return suggest(env, ctx, body, name);
	},

	"GET /v1/global": async (env, ctx, req) => global(env, ctx, await optionalCaller(env, req)),

	"GET /v1/me": authed(async (env, _ctx, caller) => profile(env, caller)),
	"POST /v1/me/sync": authed(async (env, ctx, caller, req) => sync(env, ctx, caller, await readBody(req))),
	"POST /v1/me/privacy": authed(async (env, ctx, caller, req) => setPrivacy(env, ctx, caller, await readBody(req))),
	"POST /v1/me/delete": authed(async (env, ctx, caller) => deleteMe(env, ctx, caller)),
	"POST /v1/presence": authed(async (env, ctx, caller, req) => presence(env, ctx, caller, await readBody(req))),

	// Live connection: the game opens a WebSocket here and receives "something changed" events.
	"GET /v1/live": authed(async (env, _ctx, caller, req) => {
		if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") {
			throw new HttpError(426, "Expected a WebSocket upgrade");
		}
		// Counts as "recently seen", so events reach this player even before their first heartbeat.
		await env.DB.prepare("UPDATE players SET last_seen = ? WHERE uuid = ?").bind(Date.now(), caller.uuid).run();
		// The verified account, set here so a client can never claim another one.
		const forward = new Request(req);
		forward.headers.set("x-siq-uuid", caller.uuid);
		return env.LIVE.getByName(caller.uuid).fetch(forward);
	}),

	"GET /v1/friends": authed(async (env, _ctx, caller) => friends(env, caller)),
	"POST /v1/friends/request": authed(async (env, ctx, caller, req) => request(env, ctx, caller, await readBody(req))),
	"POST /v1/friends/respond": authed(async (env, ctx, caller, req) => respond(env, ctx, caller, await readBody(req))),
	"POST /v1/friends/cancel": authed(async (env, ctx, caller, req) => cancel(env, ctx, caller, await readBody(req))),
	"POST /v1/friends/remove": authed(async (env, ctx, caller, req) => remove(env, ctx, caller, await readBody(req))),
	"POST /v1/friends/block": authed(async (env, ctx, caller, req) => block(env, ctx, caller, await readBody(req))),
	"POST /v1/friends/unblock": authed(async (env, _ctx, caller, req) => unblock(env, caller, await readBody(req))),

	"GET /v1/chat": authed(async (env, _ctx, caller, _req, url) => conversation(env, caller, url)),
	"POST /v1/chat/send": authed(async (env, ctx, caller, req) => send(env, ctx, caller, await readBody(req))),

	"GET /v1/admin/overview": authed(async (env, _ctx, caller) => adminOverview(env, caller)),
	"GET /v1/admin/suggestions": authed(async (env, _ctx, caller, _req, url) => adminSuggestions(env, caller, url)),
	"POST /v1/admin/suggestions/update": authed(async (env, _ctx, caller, req) => adminUpdateSuggestion(env, caller, await readBody(req))),
};

export default {
	async fetch(request, env, ctx): Promise<Response> {
		const url = new URL(request.url);
		const handler = routes[`${request.method} ${url.pathname}`];
		if (!handler) {
			return fail(404, "Not found");
		}
		try {
			const result = await handler(env, ctx, request, url);
			return result instanceof Response ? result : json(result);
		} catch (e) {
			if (e instanceof HttpError) {
				return fail(e.status, e.message);
			}
			console.error(JSON.stringify({ msg: "unhandled error", route: url.pathname, error: e instanceof Error ? e.message : String(e) }));
			return fail(500, "Something went wrong on the SessionIQ server");
		}
	},

	async scheduled(_controller, env, ctx): Promise<void> {
		const cutoff = Date.now() - Number(env.MESSAGE_RETENTION_DAYS) * 24 * 3600 * 1000;
		ctx.waitUntil(env.DB.prepare("DELETE FROM messages WHERE at < ?").bind(cutoff).run());
		// Connections that vanished without a close event (the online count already ignores them).
		ctx.waitUntil(
			env.DB.prepare("DELETE FROM live_sessions WHERE uuid NOT IN (SELECT uuid FROM players WHERE IFNULL(last_seen, 0) > ?)")
				.bind(Date.now() - 15 * 60 * 1000)
				.run(),
		);
		await refreshSnapshot(env.DB);
	},
} satisfies ExportedHandler<Env>;
