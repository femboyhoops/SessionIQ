package com.sessioniq.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.model.ServerMeta;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class ServerRegistry {
	private final DataPaths paths;
	private final StorageService storage;
	private final Map<String, ServerMeta> entries = new HashMap<>();

	public ServerRegistry(DataPaths paths, StorageService storage) {
		this.paths = paths;
		this.storage = storage;
	}

	public static Map<String, ServerMeta> read(DataPaths paths, List<String> problems) {
		Map<String, ServerMeta> out = new HashMap<>();
		if (!Files.exists(paths.registry)) {
			return out;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(paths.registry, StandardCharsets.UTF_8));
			JsonObject servers = root.getAsJsonObject().getAsJsonObject("servers");
			if (servers != null) {
				for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
					try {
						out.put(e.getKey(), Json.fromTree(e.getValue().getAsJsonObject(), ServerMeta.class));
					} catch (Exception ex) {
						problems.add("Server registry entry " + e.getKey() + " was invalid and was skipped");
					}
				}
			}
		} catch (Exception e) {
			try {
				AtomicFiles.quarantine(paths.registry, paths.quarantine);
				problems.add("servers/registry.json could not be parsed; a copy was preserved in quarantine");
			} catch (IOException io) {
				problems.add("servers/registry.json could not be parsed or copied: " + io.getMessage());
			}
		}
		return out;
	}

	public void publish(Map<String, ServerMeta> loaded) {
		entries.putAll(loaded);
	}

	public void replaceAll(Map<String, ServerMeta> loaded) {
		entries.clear();
		entries.putAll(loaded);
	}

	public ServerMeta get(String key) {
		return entries.get(key);
	}

	public String nickname(String key) {
		ServerMeta m = entries.get(key);
		return m == null || m.nickname == null || m.nickname.isBlank() ? null : m.nickname;
	}

	public String group(String key) {
		ServerMeta m = entries.get(key);
		return m == null || m.group == null || m.group.isBlank() ? null : m.group;
	}

	public TreeSet<String> groups() {
		TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (ServerMeta m : entries.values()) {
			if (m.group != null && !m.group.isBlank()) {
				out.add(m.group);
			}
		}
		return out;
	}

	public void setNickname(String key, String nickname) {
		entries.computeIfAbsent(key, k -> new ServerMeta()).nickname = blankToNull(nickname);
		cleanup(key);
		save();
	}

	public void setGroup(String key, String group) {
		entries.computeIfAbsent(key, k -> new ServerMeta()).group = blankToNull(group);
		cleanup(key);
		save();
	}

	public void remove(String key) {
		if (entries.remove(key) != null) {
			save();
		}
	}

	public void clear() {
		entries.clear();
		save();
	}

	public Map<String, ServerMeta> all() {
		return entries;
	}

	private void cleanup(String key) {
		ServerMeta m = entries.get(key);
		if (m != null && m.nickname == null && m.group == null && (m.unknownFields == null || m.unknownFields.isEmpty())) {
			entries.remove(key);
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	public void save() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		JsonObject servers = new JsonObject();
		for (Map.Entry<String, ServerMeta> e : new java.util.TreeMap<>(entries).entrySet()) {
			servers.add(e.getKey(), JsonParser.parseString(Json.write(e.getValue(), false)));
		}
		root.add("servers", servers);
		storage.writeAtomic(paths.registry, Json.PRETTY.toJson(root));
	}
}
