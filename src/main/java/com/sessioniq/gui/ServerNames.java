package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.track.ServerIdentity;

import java.util.HashMap;
import java.util.Map;

public final class ServerNames {
	private static int builtFor = -1;
	private static final Map<String, String> NAMES = new HashMap<>();
	private static final Map<String, String> ADDRESSES = new HashMap<>();
	private static final Map<String, Long> SEEN = new HashMap<>();

	private ServerNames() {
	}

	private static void ensure() {
		SessionIQ siq = SessionIQ.get();
		int rev = siq.sessions.revision();
		if (rev == builtFor) {
			return;
		}
		builtFor = rev;
		NAMES.clear();
		ADDRESSES.clear();
		SEEN.clear();
		boolean merge = siq.config.get().mergePorts;
		for (SessionRecord s : siq.sessions.sorted()) {
			for (VisitRecord v : s.visits) {
				String key = ServerIdentity.groupKey(v, merge);
				record(key, v.name, v.address, v.joinedAt);
				if (!key.equals(v.serverKey)) {
					record(v.serverKey, v.name, v.address, v.joinedAt);
				}
			}
			for (DisconnectRecord d : s.disconnects) {
				if (d.serverKey != null) {
					record(d.serverKey, d.serverName, d.address, 0);
					record(ServerIdentity.groupKey(d.serverKey, merge), d.serverName, d.address, 0);
				}
			}
		}
		findDuplicates();
	}

	private static void record(String key, String name, String address, long at) {
		Long seen = SEEN.get(key);
		if (seen == null || at > seen) {
			SEEN.put(key, at);
			if (name != null) {
				NAMES.put(key, name);
			}
			if (address != null) {
				ADDRESSES.put(key, address);
			}
		}
	}

	public static void invalidate() {
		builtFor = -1;
	}

	public static String name(String key) {
		if (key == null) {
			return "Unknown";
		}
		SessionIQ siq = SessionIQ.get();
		String nick = siq.servers.nickname(key);
		if (nick != null) {
			return nick;
		}
		ensure();
		String n = NAMES.get(key);
		if (n == null || n.isBlank()) {
			return fallback(key);
		}
		if (DUPLICATES.contains(n.toLowerCase(java.util.Locale.ROOT)) && key.startsWith("mp:")) {
			String hostPort = key.substring(3);
			if (siq.config.get().showAddresses) {
				return n + " (" + hostPort + ")";
			}
			int colon = hostPort.lastIndexOf(':');
			return colon >= 0 ? n + " (port " + hostPort.substring(colon + 1) + ")" : n;
		}
		return n;
	}

	private static final java.util.Set<String> DUPLICATES = new java.util.HashSet<>();

	private static void findDuplicates() {
		DUPLICATES.clear();
		Map<String, java.util.Set<String>> keysByName = new HashMap<>();
		for (Map.Entry<String, String> e : NAMES.entrySet()) {
			keysByName.computeIfAbsent(e.getValue().toLowerCase(java.util.Locale.ROOT), k -> new java.util.HashSet<>()).add(e.getKey());
		}
		for (Map.Entry<String, java.util.Set<String>> e : keysByName.entrySet()) {
			if (e.getValue().size() > 1) {
				DUPLICATES.add(e.getKey());
			}
		}
	}

	public static String recordedName(String key) {
		ensure();
		String n = NAMES.get(key);
		return n != null ? n : fallback(key);
	}

	private static String fallback(String key) {
		if (key.startsWith("mp:")) {
			String rest = key.substring(3);
			return rest.endsWith(":" + ServerIdentity.DEFAULT_PORT) ? rest.substring(0, rest.length() - 6) : rest;
		}
		if (key.startsWith("sp:") || key.startsWith("realms:")) {
			return key.substring(key.indexOf(':') + 1);
		}
		return key;
	}

	public static String address(String key) {
		if (key == null || ServerIdentity.isWorld(key)) {
			return "Single-player world";
		}
		if (!SessionIQ.get().config.get().showAddresses) {
			return "Address hidden";
		}
		ensure();
		String a = ADDRESSES.get(key);
		if (a != null) {
			return a;
		}
		return fallback(key);
	}

	public static String maskedAddress(String address) {
		if (address == null) {
			return "—";
		}
		return SessionIQ.get().config.get().showAddresses ? address : "Address hidden";
	}
}
