package com.sessioniq.track;

import com.sessioniq.model.VisitRecord;

import java.util.Locale;

public final class ServerIdentity {
	public static final int DEFAULT_PORT = 25565;

	private ServerIdentity() {
	}

	public static String multiplayerKey(String host, int port) {
		return "mp:" + normalizeHost(host) + ":" + (port > 0 ? port : DEFAULT_PORT);
	}

	public static String realmsKey(String realmName) {
		return "realms:" + (realmName == null ? "" : realmName.trim());
	}

	public static String worldKey(String saveFolder) {
		return "sp:" + saveFolder;
	}

	public static String normalizeHost(String host) {
		if (host == null) {
			return "";
		}
		String h = host.trim().toLowerCase(Locale.ROOT);
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		return h;
	}

	public static String groupKey(VisitRecord v, boolean mergePorts) {
		if (mergePorts && v.serverKey != null && v.serverKey.startsWith("mp:") && v.host != null) {
			return "mp:" + normalizeHost(v.host);
		}
		return v.serverKey;
	}

	public static String groupKey(String serverKey, boolean mergePorts) {
		if (mergePorts && serverKey != null && serverKey.startsWith("mp:")) {
			int idx = serverKey.lastIndexOf(':');
			if (idx > 3) {
				return serverKey.substring(0, idx);
			}
		}
		return serverKey;
	}

	public static boolean isWorld(String key) {
		return key != null && key.startsWith("sp:");
	}
}
