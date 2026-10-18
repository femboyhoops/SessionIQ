package com.sessioniq.online;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which server addresses may leave the computer and the name they are counted under.
 * Only real public hostnames qualify: IP addresses, localhost, LAN names and anything without a
 * real top-level domain return null. Subdomains are grouped under their registrable domain, so
 * londs.mcpvp.com and fra.mcpvp.com both become mcpvp.com. The online service applies the same
 * rules again (backend/src/hosts.ts); keep the two lists in sync.
 */
public final class PublicHost {
	private static final List<String> PRIVATE_SUFFIXES = List.of(".local", ".lan", ".home", ".internal", ".localhost", ".test", ".invalid", ".example",
		".arpa", ".localdomain");
	/** Country suffixes with two labels: example.co.uk is the server, not co.uk. */
	private static final Set<String> TWO_LABEL_SUFFIXES = Set.of(
		"co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "ac.uk",
		"com.au", "net.au", "org.au", "co.nz", "net.nz", "org.nz", "co.za", "org.za",
		"com.br", "net.br", "org.br", "com.ar", "com.mx", "com.co", "com.pe", "com.uy", "com.ve", "com.ec",
		"com.tr", "net.tr", "org.tr", "co.jp", "ne.jp", "or.jp", "com.cn", "net.cn", "org.cn", "com.tw", "com.hk",
		"co.kr", "or.kr", "co.in", "net.in", "org.in", "co.id", "com.sg", "com.my", "com.ph", "com.vn",
		"com.pl", "net.pl", "org.pl", "com.ua", "co.il", "com.ru", "com.es", "com.pt", "co.at", "or.at", "co.hu",
		"eu.org", "us.org", "us.com", "uk.com", "eu.com");
	/** Free hosting and tunnel domains where every subdomain is a different server, so they are never merged. */
	private static final List<String> HOSTING_SUFFIXES = List.of(
		"aternos.me", "aternos.host", "minehut.gg", "minehut.com", "exaroton.me", "falixsrv.me", "falix.gg", "falixserver.net",
		"ploudos.me", "minefort.com", "feathermc.gg", "e4mc.link", "playit.gg", "ply.gg", "joinmc.link", "ngrok.io",
		"ngrok-free.app", "ddns.net", "duckdns.org", "no-ip.org", "no-ip.biz", "hopto.org", "zapto.org", "serveminecraft.net",
		"servegame.com", "mcpro.io", "my.pebble.host", "apexmc.co", "sparked.network", "mcserver.us", "freemcserver.net",
		"mcsh.io", "minecraft.best", "mc-srv.com", "serv.nu", "tcpshield.com", "dynu.net", "freeddns.org");
	private static final Pattern CHARS = Pattern.compile("^[a-z0-9.-]+$");
	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
	private static final Pattern TLD = Pattern.compile("^([a-z]{2,63}|xn--[a-z0-9-]+)$");

	private PublicHost() {
	}

	public static String of(String raw) {
		if (raw == null) {
			return null;
		}
		String h = raw.trim().toLowerCase(Locale.ROOT);
		int colon = h.lastIndexOf(':');
		if (colon > 0 && h.indexOf(':') == colon) {
			h = h.substring(0, colon);
		}
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		if (h.length() < 4 || h.length() > 253 || !CHARS.matcher(h).matches() || h.equals("localhost") || IPV4.matcher(h).matches()) {
			return null;
		}
		for (String s : PRIVATE_SUFFIXES) {
			if (h.endsWith(s)) {
				return null;
			}
		}
		String[] labels = h.split("\\.", -1);
		if (labels.length < 2) {
			return null;
		}
		for (String l : labels) {
			if (l.isEmpty() || l.length() > 63 || l.startsWith("-") || l.endsWith("-")) {
				return null;
			}
		}
		if (!TLD.matcher(labels[labels.length - 1]).matches()) {
			return null;
		}
		int suffixLabels = TWO_LABEL_SUFFIXES.contains(labels[labels.length - 2] + "." + labels[labels.length - 1]) ? 2 : 1;
		for (String s : HOSTING_SUFFIXES) {
			if (h.endsWith("." + s)) {
				suffixLabels = Math.max(suffixLabels, s.split("\\.").length);
			}
		}
		if (labels.length <= suffixLabels) {
			return h;
		}
		return String.join(".", java.util.Arrays.copyOfRange(labels, labels.length - suffixLabels - 1, labels.length));
	}
}
