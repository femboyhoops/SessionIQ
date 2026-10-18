export class HttpError extends Error {
	constructor(
		readonly status: number,
		message: string,
	) {
		super(message);
	}
}

const MAX_BODY_BYTES = 64 * 1024;

export function json(data: unknown, status = 200): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
	});
}

export function fail(status: number, message: string): Response {
	return json({ error: message }, status);
}

export type Body = Record<string, unknown>;

/** Reads a JSON object body, refusing anything over 64 KB without buffering it. */
export async function readBody(request: Request): Promise<Body> {
	const declared = Number(request.headers.get("content-length") ?? "0");
	if (declared > MAX_BODY_BYTES) {
		throw new HttpError(413, "Request body too large");
	}
	if (!request.body) {
		return {};
	}
	const reader = request.body.getReader();
	const chunks: Uint8Array[] = [];
	let size = 0;
	for (;;) {
		const { done, value } = await reader.read();
		if (done) {
			break;
		}
		size += value.byteLength;
		if (size > MAX_BODY_BYTES) {
			await reader.cancel();
			throw new HttpError(413, "Request body too large");
		}
		chunks.push(value);
	}
	const bytes = new Uint8Array(size);
	let offset = 0;
	for (const c of chunks) {
		bytes.set(c, offset);
		offset += c.byteLength;
	}
	if (size === 0) {
		return {};
	}
	let parsed: unknown;
	try {
		parsed = JSON.parse(new TextDecoder().decode(bytes));
	} catch {
		throw new HttpError(400, "Body is not valid JSON");
	}
	if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
		throw new HttpError(400, "Body must be a JSON object");
	}
	return parsed as Body;
}

export function str(body: Body, key: string, min: number, max: number): string {
	const v = body[key];
	if (typeof v !== "string") {
		throw new HttpError(400, `${key} must be a string`);
	}
	const t = v.trim();
	if (t.length < min || t.length > max) {
		throw new HttpError(400, `${key} must be ${min}-${max} characters`);
	}
	return t;
}

export function optStr(body: Body, key: string, max: number): string | null {
	const v = body[key];
	if (v === undefined || v === null || v === "") {
		return null;
	}
	return str(body, key, 0, max);
}

export function int(body: Body, key: string, max = Number.MAX_SAFE_INTEGER): number {
	const v = body[key];
	if (typeof v !== "number" || !Number.isInteger(v) || v < 0 || v > max) {
		throw new HttpError(400, `${key} must be a non-negative integer`);
	}
	return v;
}

export function bool(body: Body, key: string): boolean {
	const v = body[key];
	if (typeof v !== "boolean") {
		throw new HttpError(400, `${key} must be true or false`);
	}
	return v;
}

/**
 * Removes control characters (including newlines) and Minecraft's § formatting codes so text is
 * always one plain line when the mod draws it.
 */
export function plain(text: string): string {
	return text
		.replace(/§./g, "")
		.replace(/[\u0000-\u001f\u007f-\u009f§​-‏‪-‮⁦-⁩]/g, " ")
		.replace(/\s+/g, " ")
		.trim();
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function uuid(body: Body, key: string): string {
	const v = body[key];
	if (typeof v !== "string" || !UUID.test(v.toLowerCase())) {
		throw new HttpError(400, `${key} must be a UUID`);
	}
	return v.toLowerCase();
}

export function isUuid(v: string | null): v is string {
	return v !== null && UUID.test(v);
}

export function dashed(undashed: string): string {
	const s = undashed.toLowerCase();
	return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}

export function utcDay(ms: number): string {
	return new Date(ms).toISOString().slice(0, 10);
}
