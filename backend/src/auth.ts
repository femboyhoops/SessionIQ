import { Body, HttpError, str, uuid } from "./http";
import { PLAYER_CERTIFICATE_KEYS } from "./mojang-keys";

const CHALLENGE_TTL_S = 120;
const TOKEN_TTL_S = 30 * 24 * 3600;
const encoder = new TextEncoder();

export interface Caller {
	uuid: string;
	admin: boolean;
}

async function hmacKey(env: Env): Promise<CryptoKey> {
	if (!env.TOKEN_SECRET || env.TOKEN_SECRET.length < 16) {
		throw new HttpError(503, "The server is missing its TOKEN_SECRET");
	}
	return crypto.subtle.importKey("raw", encoder.encode(env.TOKEN_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

function hex(bytes: ArrayBuffer): string {
	return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64url(bytes: ArrayBuffer): string {
	let s = "";
	for (const b of new Uint8Array(bytes)) {
		s += String.fromCharCode(b);
	}
	return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromB64url(s: string): Uint8Array<ArrayBuffer> | null {
	try {
		const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
		const out = new Uint8Array(bin.length);
		for (let i = 0; i < bin.length; i++) {
			out[i] = bin.charCodeAt(i);
		}
		return out;
	} catch {
		return null;
	}
}

async function challengeMac(env: Env, playerUuid: string, ts: string): Promise<string> {
	const sig = await crypto.subtle.sign("HMAC", await hmacKey(env), encoder.encode(`challenge|${playerUuid}|${ts}`));
	return hex(sig).slice(0, 32);
}

/**
 * Step 1 of sign-in: a one-time nonce for the game to sign. It is stateless (8 hex digits of
 * time plus a MAC bound to the UUID) and expires after two minutes.
 */
export async function challenge(env: Env, body: Body): Promise<{ nonce: string }> {
	const playerUuid = uuid(body, "uuid");
	const ts = Math.floor(Date.now() / 1000).toString(16).padStart(8, "0");
	return { nonce: ts + (await challengeMac(env, playerUuid, ts)) };
}

function devAuthAllowed(env: Env, url: URL): boolean {
	return env.ALLOW_DEV_AUTH === "1" && (url.hostname === "localhost" || url.hostname === "127.0.0.1");
}

/** The exact text the game signs with its player key. */
export function signInMessage(playerUuid: string, nonce: string): string {
	return `SessionIQ sign-in\n${playerUuid}\n${nonce}`;
}

let certificateKeys: Promise<CryptoKey[]> | null = null;

function importCertificateKey(b64: string): Promise<CryptoKey> {
	return crypto.subtle.importKey("spki", fromB64url(b64) ?? new Uint8Array(), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
}

async function mojangCertificateKeys(env: Env, url: URL): Promise<CryptoKey[]> {
	certificateKeys ??= Promise.all(PLAYER_CERTIFICATE_KEYS.map(importCertificateKey));
	const keys = await certificateKeys;
	// Local tests only: a stand-in for Mojang's key so test/smoke.mjs can issue certificates.
	if (devAuthAllowed(env, url) && env.DEV_CERT_KEY) {
		return [...keys, await importCertificateKey(env.DEV_CERT_KEY)];
	}
	return keys;
}

function uuidBytes(playerUuid: string): Uint8Array {
	const hexDigits = playerUuid.replace(/-/g, "");
	const out = new Uint8Array(16);
	for (let i = 0; i < 16; i++) {
		out[i] = parseInt(hexDigits.slice(i * 2, i * 2 + 2), 16);
	}
	return out;
}

/**
 * Checks that Mojang issued this player key to this UUID. Mojang signs (UUID, expiry, key) for
 * secure chat, exactly the payload PlayerPublicKey.PublicKeyData#verifyKey checks in the game.
 */
async function mojangIssued(
	keys: CryptoKey[],
	playerUuid: string,
	publicKey: Uint8Array<ArrayBuffer>,
	expiresAt: number,
	keySignature: Uint8Array<ArrayBuffer>,
): Promise<boolean> {
	const data = new Uint8Array(24 + publicKey.length);
	data.set(uuidBytes(playerUuid), 0);
	new DataView(data.buffer).setBigInt64(16, BigInt(expiresAt));
	data.set(publicKey, 24);
	for (const key of keys) {
		if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, keySignature, data)) {
			return true;
		}
	}
	return false;
}

/** The account's current username, from playerdb.co (Mojang's own API is blocked from Cloudflare). */
async function lookupName(playerUuid: string): Promise<string> {
	let res: Response;
	try {
		res = await fetch(`https://playerdb.co/api/player/minecraft/${playerUuid}`, { headers: { "user-agent": "SessionIQ-API/1.1 (+https://github.com/femboyhoops/SessionIQ)" } });
	} catch {
		throw new HttpError(503, "Could not look up your username right now; try again in a minute");
	}
	if (!res.ok) {
		throw new HttpError(res.status === 400 || res.status === 404 ? 401 : 503, "Could not look up your username right now; try again in a minute");
	}
	const data = (await res.json()) as { data?: { player?: { id?: string; username?: string } } };
	const player = data.data?.player;
	if (!player?.username || player.id?.toLowerCase() !== playerUuid || !/^[0-9A-Za-z_]{1,16}$/.test(player.username)) {
		throw new HttpError(401, "Could not confirm your Minecraft account");
	}
	return player.username;
}

/**
 * Step 2 of sign-in. The game signs the nonce with the private half of the player key that
 * Mojang issued for secure chat, and sends the public key with Mojang's signature over it.
 * No Minecraft login token is involved and nothing is sent to Mojang.
 */
export async function verify(env: Env, body: Body, url: URL): Promise<{ uuid: string; name: string; token: string; expiresAt: number; admin: boolean }> {
	const playerUuid = uuid(body, "uuid");
	const name = str(body, "name", 1, 16);
	const nonce = str(body, "nonce", 40, 40);
	if (!/^[0-9a-zA-Z_]{1,16}$/.test(name)) {
		throw new HttpError(400, "Invalid username");
	}
	const ts = nonce.slice(0, 8);
	const age = Math.floor(Date.now() / 1000) - parseInt(ts, 16);
	if (!/^[0-9a-f]{40}$/.test(nonce) || age < -10 || age > CHALLENGE_TTL_S || nonce.slice(8) !== (await challengeMac(env, playerUuid, ts))) {
		throw new HttpError(401, "Sign-in challenge expired or invalid; try again");
	}
	let verifiedName = name;
	if (!(devAuthAllowed(env, url) && body.publicKey === undefined)) {
		const publicKey = fromB64url(str(body, "publicKey", 100, 2000));
		const keySignature = fromB64url(str(body, "keySignature", 100, 2000));
		const signature = fromB64url(str(body, "signature", 100, 2000));
		const keyExpiresAt = body.keyExpiresAt;
		if (!publicKey || !keySignature || !signature || typeof keyExpiresAt !== "number" || !Number.isSafeInteger(keyExpiresAt)) {
			throw new HttpError(400, "Malformed sign-in proof");
		}
		if (keyExpiresAt < Date.now()) {
			throw new HttpError(401, "Your Minecraft profile key has expired; restart the game and try again");
		}
		if (!(await mojangIssued(await mojangCertificateKeys(env, url), playerUuid, publicKey, keyExpiresAt, keySignature))) {
			throw new HttpError(401, "This profile key was not issued by Mojang for this account");
		}
		let playerKey: CryptoKey;
		try {
			playerKey = await crypto.subtle.importKey("spki", publicKey, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
		} catch {
			throw new HttpError(400, "Malformed profile key");
		}
		if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, signature, encoder.encode(signInMessage(playerUuid, nonce))))) {
			throw new HttpError(401, "Sign-in signature is invalid");
		}
		// Local tests use made-up accounts that playerdb.co does not know.
		verifiedName = devAuthAllowed(env, url) ? name : await lookupName(playerUuid);
	}
	const expiresAt = Math.floor(Date.now() / 1000) + TOKEN_TTL_S;
	const payload = `v1.${playerUuid}.${expiresAt}`;
	const sig = await crypto.subtle.sign("HMAC", await hmacKey(env), encoder.encode(payload));
	return { uuid: playerUuid, name: verifiedName, token: `${payload}.${b64url(sig)}`, expiresAt: expiresAt * 1000, admin: isAdmin(env, playerUuid) };
}

export function isAdmin(env: Env, playerUuid: string): boolean {
	const admin: string = env.ADMIN_UUID;
	return admin.length > 0 && admin.toLowerCase() === playerUuid;
}

/** Returns the caller of a request with a valid bearer token, or throws 401. */
export async function authenticate(env: Env, request: Request): Promise<Caller> {
	const header = request.headers.get("authorization") ?? "";
	const token = header.startsWith("Bearer ") ? header.slice(7) : "";
	const parts = token.split(".");
	if (parts.length !== 4 || parts[0] !== "v1") {
		throw new HttpError(401, "Not signed in");
	}
	const sig = fromB64url(parts[3]);
	const ok = sig !== null && (await crypto.subtle.verify("HMAC", await hmacKey(env), sig, encoder.encode(parts.slice(0, 3).join("."))));
	if (!ok || Number(parts[2]) * 1000 < Date.now()) {
		throw new HttpError(401, "Session expired; sign in again");
	}
	return { uuid: parts[1], admin: isAdmin(env, parts[1]) };
}
