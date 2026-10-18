import { DurableObject } from "cloudflare:workers";

const MAX_SOCKETS = 3;

/**
 * One instance per signed-in player, holding that player's live connection(s) from the game.
 * The Worker calls push() when something the player should see changed (a friend request, a
 * message, a friend going online...). Events only say what changed; the game then reloads it
 * through the normal API, so nothing private is stored here.
 *
 * Uses the hibernation API: idle connections cost nothing, and "ping" keep-alives are answered
 * without waking the object.
 */
export class LiveHub extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
	}

	async fetch(request: Request): Promise<Response> {
		if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
			return new Response("Expected a WebSocket upgrade", { status: 426 });
		}
		const existing = this.ctx.getWebSockets();
		for (const old of existing.slice(0, Math.max(0, existing.length - MAX_SOCKETS + 1))) {
			try {
				old.close(4000, "Replaced by a newer connection");
			} catch {
				// Already closed.
			}
		}
		const playerUuid = request.headers.get("x-siq-uuid") ?? "";
		const pair = new WebSocketPair();
		this.ctx.acceptWebSocket(pair[1]);
		// Survives hibernation, so the close handler still knows whose connection it was.
		pair[1].serializeAttachment({ uuid: playerUuid });
		pair[1].send(JSON.stringify({ t: "hello" }));
		if (playerUuid) {
			await this.track(playerUuid, true);
		}
		return new Response(null, { status: 101, webSocket: pair[0] });
	}

	/**
	 * Keeps live_sessions in step with this player's connections and sends the new "online now"
	 * count to the admin, but only while the admin's game is connected.
	 */
	private async track(playerUuid: string, online: boolean): Promise<void> {
		try {
			const db = this.env.DB;
			if (online) {
				await db.prepare("INSERT OR REPLACE INTO live_sessions (uuid, connected_at) VALUES (?, ?)").bind(playerUuid, Date.now()).run();
			} else {
				await db.prepare("DELETE FROM live_sessions WHERE uuid = ?").bind(playerUuid).run();
			}
			const admin = this.env.ADMIN_UUID.toLowerCase();
			if (!admin) {
				return;
			}
			const [count, adminLive] = await db.batch([
				onlineNowQuery(db),
				db.prepare("SELECT 1 AS x FROM live_sessions WHERE uuid = ?").bind(admin),
			]);
			if (adminLive.results.length > 0) {
				const event = JSON.stringify({ t: "online", count: (count.results[0] as { n: number }).n });
				if (playerUuid === admin) {
					await this.push(event);
				} else {
					await this.env.LIVE.getByName(admin).push(event);
				}
			}
		} catch (e) {
			console.error(JSON.stringify({ msg: "live tracking failed", error: e instanceof Error ? e.message : String(e) }));
		}
	}

	/** Sends an event to every connection of this player; returns how many received it. */
	async push(event: string): Promise<number> {
		let sent = 0;
		for (const ws of this.ctx.getWebSockets()) {
			try {
				ws.send(event);
				sent++;
			} catch {
				// The connection is closing; the game reconnects and reloads on its own.
			}
		}
		return sent;
	}

	async webSocketMessage(): Promise<void> {
		// The game only sends "ping", which the auto-response answers.
	}

	async webSocketClose(ws: WebSocket, code: number): Promise<void> {
		try {
			ws.close(code === 1005 || code === 1006 ? 1000 : code, "Closed");
		} catch {
			// Already closed.
		}
		await this.closed(ws);
	}

	async webSocketError(ws: WebSocket): Promise<void> {
		await this.closed(ws);
	}

	/** When the player's last connection is gone, they no longer count as online. */
	private async closed(ws: WebSocket): Promise<void> {
		const attachment = ws.deserializeAttachment() as { uuid?: string } | null;
		const stillOpen = this.ctx.getWebSockets().some((other) => other !== ws && other.readyState === WebSocket.OPEN);
		if (attachment?.uuid && !stillOpen) {
			await this.track(attachment.uuid, false);
		}
	}
}

/**
 * Players online right now: an open live connection, and seen in the last 15 minutes as a guard
 * against rows left behind by a connection that vanished without a close event.
 */
export function onlineNowQuery(db: D1Database): D1PreparedStatement {
	return db
		.prepare("SELECT COUNT(*) AS n FROM live_sessions l JOIN players p ON p.uuid = l.uuid WHERE IFNULL(p.last_seen, 0) > ?")
		.bind(Date.now() - 15 * 60 * 1000);
}

export type LiveEvent =
	| { t: "friends" }
	| { t: "message"; from: string; name: string; text: string }
	| { t: "online"; count: number };

const RECENTLY_SEEN_MS = 15 * 60 * 1000;

/**
 * Pushes an event to players who may be connected (seen in the last 15 minutes), after the
 * response has been sent. Offline players are skipped; they load everything when they connect.
 */
export function notify(env: Env, ctx: ExecutionContext, uuids: string[], event: LiveEvent): void {
	const targets = [...new Set(uuids)];
	if (targets.length === 0) {
		return;
	}
	ctx.waitUntil(
		(async () => {
			try {
				const { results } = await env.DB.prepare(
					`SELECT uuid FROM players WHERE uuid IN (${targets.map(() => "?").join(", ")}) AND IFNULL(last_seen, 0) > ?`,
				)
					.bind(...targets, Date.now() - RECENTLY_SEEN_MS)
					.run<{ uuid: string }>();
				const body = JSON.stringify(event);
				await Promise.all(results.map((r) => env.LIVE.getByName(r.uuid).push(body)));
			} catch (e) {
				console.error(JSON.stringify({ msg: "live notify failed", error: e instanceof Error ? e.message : String(e) }));
			}
		})(),
	);
}

/** The player's friends, for fan-out when their status, privacy or stats change. */
export async function friendsOf(db: D1Database, playerUuid: string): Promise<string[]> {
	const { results } = await db.prepare("SELECT b FROM friendships WHERE a = ?").bind(playerUuid).run<{ b: string }>();
	return results.map((r) => r.b);
}
