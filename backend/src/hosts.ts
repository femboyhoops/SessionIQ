const PRIVATE_SUFFIXES = [".local", ".lan", ".home", ".internal", ".localhost", ".test", ".invalid", ".example", ".arpa", ".localdomain"];

/**
 * Country suffixes with two labels: example.co.uk is the server, not co.uk. A small subset of
 * the Public Suffix List covering where Minecraft servers usually live.
 * Keep in sync with PublicHost.java in the mod.
 */
const TWO_LABEL_SUFFIXES = new Set([
	"co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "ac.uk",
	"com.au", "net.au", "org.au", "co.nz", "net.nz", "org.nz", "co.za", "org.za",
	"com.br", "net.br", "org.br", "com.ar", "com.mx", "com.co", "com.pe", "com.uy", "com.ve", "com.ec",
	"com.tr", "net.tr", "org.tr", "co.jp", "ne.jp", "or.jp", "com.cn", "net.cn", "org.cn", "com.tw", "com.hk",
	"co.kr", "or.kr", "co.in", "net.in", "org.in", "co.id", "com.sg", "com.my", "com.ph", "com.vn",
	"com.pl", "net.pl", "org.pl", "com.ua", "co.il", "com.ru", "com.es", "com.pt", "co.at", "or.at", "co.hu",
	"eu.org", "us.org", "us.com", "uk.com", "eu.com",
]);

/**
 * Free hosting and tunnel domains where every subdomain is a different server
 * (myserver.aternos.me), so these are never merged into one.
 */
const HOSTING_SUFFIXES = [
	"aternos.me", "aternos.host", "minehut.gg", "minehut.com", "exaroton.me", "falixsrv.me", "falix.gg", "falixserver.net",
	"ploudos.me", "minefort.com", "feathermc.gg", "e4mc.link", "playit.gg", "ply.gg", "joinmc.link", "ngrok.io",
	"ngrok-free.app", "ddns.net", "duckdns.org", "no-ip.org", "no-ip.biz", "hopto.org", "zapto.org", "serveminecraft.net",
	"servegame.com", "mcpro.io", "my.pebble.host", "apexmc.co", "sparked.network", "mcserver.us", "freemcserver.net",
	"mcsh.io", "minecraft.best", "mc-srv.com", "serv.nu", "tcpshield.com", "dynu.net", "freeddns.org",
];

/**
 * Returns the public name a server is counted under, or null when the address is private:
 * IP addresses, localhost, LAN names and anything without a real top-level domain are never
 * stored. Subdomains are grouped under their registrable domain, so londs.mcpvp.com and
 * fra.mcpvp.com both count as mcpvp.com.
 */
export function publicHost(raw: string): string | null {
	let h = raw.trim().toLowerCase();
	const colon = h.lastIndexOf(":");
	if (colon > 0 && h.indexOf(":") === colon) {
		h = h.slice(0, colon);
	}
	h = h.replace(/\.+$/, "");
	if (h.length < 4 || h.length > 253 || !/^[a-z0-9.-]+$/.test(h) || h === "localhost") {
		return null;
	}
	if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) {
		return null;
	}
	if (PRIVATE_SUFFIXES.some((s) => h.endsWith(s))) {
		return null;
	}
	const labels = h.split(".");
	if (labels.length < 2 || labels.some((l) => l.length === 0 || l.length > 63 || l.startsWith("-") || l.endsWith("-"))) {
		return null;
	}
	if (!/^[a-z]{2,63}$/.test(labels[labels.length - 1]) && !/^xn--[a-z0-9-]+$/.test(labels[labels.length - 1])) {
		return null;
	}
	let suffixLabels = TWO_LABEL_SUFFIXES.has(labels.slice(-2).join(".")) ? 2 : 1;
	for (const s of HOSTING_SUFFIXES) {
		if (h.endsWith("." + s)) {
			suffixLabels = Math.max(suffixLabels, s.split(".").length);
		}
	}
	return labels.length > suffixLabels ? labels.slice(-(suffixLabels + 1)).join(".") : h;
}
