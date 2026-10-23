package com.sessioniq.online;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.SiqLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A random ID shared by every Minecraft instance on this computer, so a player's accounts and
 * instances count as one player in Global Statistics (only the account in use is listed). It is
 * the only file SessionIQ keeps outside the game folder: ~/.sessioniq/device.json. Nothing else is
 * read from or written there.
 */
public final class DeviceId {
	private DeviceId() {
	}

	public static Path file() {
		// Tests point this elsewhere so they never touch the real user folder.
		String override = System.getProperty("sessioniq.deviceFile");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return Path.of(System.getProperty("user.home"), ".sessioniq", "device.json");
	}

	/** Reads the ID, creating it on first use. Falls back to the given ID when the file cannot be used. */
	public static String load(String fallback) {
		Path file = file();
		try {
			if (Files.exists(file)) {
				String id = null;
				try {
					JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
					id = o.has("deviceId") ? o.get("deviceId").getAsString() : null;
				} catch (RuntimeException damaged) {
					// Not valid JSON: replaced with a new ID below.
				}
				if (id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
					return id;
				}
			}
			return write(file, UUID.randomUUID().toString());
		} catch (Exception e) {
			SiqLog.warn("SessionIQ could not use {} ({}); this instance counts as its own player", file, e.toString());
			return fallback;
		}
	}

	/** Deletes the file (used by "Delete my online data"); a new random ID is made only if online features are used again. */
	public static void forget() {
		try {
			Files.deleteIfExists(file());
		} catch (Exception e) {
			SiqLog.warn("SessionIQ could not delete {}: {}", file(), e.toString());
		}
	}

	private static String write(Path file, String id) throws java.io.IOException {
		Files.createDirectories(file.getParent());
		JsonObject o = new JsonObject();
		o.addProperty("deviceId", id);
		o.addProperty("about", "Created by the SessionIQ Minecraft mod. Links your Minecraft instances on this computer so they count as one player "
			+ "in SessionIQ's Global Statistics. Safe to delete.");
		AtomicFiles.writeString(file, o.toString());
		return id;
	}
}
