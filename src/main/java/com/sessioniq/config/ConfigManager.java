package com.sessioniq.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;
import com.sessioniq.util.SiqLog;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConfigManager {
	private final Path file;
	private final Path quarantineDir;
	private SiqConfig config = new SiqConfig();
	private final List<String> issues = new CopyOnWriteArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	public ConfigManager(Path file, Path quarantineDir) {
		this.file = file;
		this.quarantineDir = quarantineDir;
	}

	public SiqConfig get() {
		return config;
	}

	public List<String> issues() {
		return issues;
	}

	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public void load() {
		issues.clear();
		if (!Files.exists(file)) {
			config = new SiqConfig();
			saveNow();
			return;
		}
		String raw;
		try {
			raw = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			issues.add("Could not read config.json (" + e.getMessage() + "); using defaults for this run without overwriting the file");
			config = new SiqConfig();
			return;
		}
		JsonObject object;
		try {
			JsonElement parsed = JsonParser.parseString(raw);
			if (!parsed.isJsonObject()) {
				throw new IllegalStateException("not a JSON object");
			}
			object = parsed.getAsJsonObject();
		} catch (Exception e) {
			preserveDamaged("config.json could not be parsed (" + e.getMessage() + ")");
			config = new SiqConfig();
			saveNow();
			return;
		}
		config = readLenient(object, issues);
		List<String> fixes = config.validate();
		issues.addAll(fixes);
		if (!issues.isEmpty()) {
			SiqLog.warn("SessionIQ config had {} recoverable problem(s); see Settings > Advanced", issues.size());
			saveNow();
		}
	}

	static SiqConfig readLenient(JsonObject object, List<String> issues) {
		SiqConfig result = new SiqConfig();
		JsonObject unknown = new JsonObject();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Field field : SiqConfig.class.getDeclaredFields()) {
			int mods = field.getModifiers();
			if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
				continue;
			}
			SerializedName sn = field.getAnnotation(SerializedName.class);
			String key = sn != null ? sn.value() : field.getName();
			seen.add(key);
			if (!object.has(key)) {
				continue;
			}
			try {
				Object value = Json.COMPACT.fromJson(object.get(key), field.getGenericType());
				if (value == null && field.getType().isPrimitive()) {
					throw new IllegalArgumentException("null");
				}
				field.set(result, value);
			} catch (Exception e) {
				issues.add("Setting '" + key + "' had an invalid value and was reset to its default");
			}
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!seen.contains(entry.getKey())) {
				unknown.add(entry.getKey(), entry.getValue());
			}
		}
		result.unknownFields = unknown.isEmpty() ? null : unknown;
		return result;
	}

	private void preserveDamaged(String problem) {
		try {
			Path copy = AtomicFiles.quarantine(file, quarantineDir);
			issues.add(problem + "; the original was preserved at " + copy.getFileName() + " and defaults were restored");
		} catch (IOException e) {
			issues.add(problem + "; the damaged file could not be copied aside (" + e.getMessage() + ")");
		}
	}

	public void saveNow() {
		try {
			AtomicFiles.writeString(file, Json.write(config, true));
		} catch (IOException e) {
			issues.add("Could not save config.json: " + e.getMessage());
			SiqLog.warn("Could not save SessionIQ config: {}", e.toString());
		}
	}

	public void changed() {
		List<String> fixes = config.validate();
		if (!fixes.isEmpty()) {
			issues.addAll(fixes);
		}
		saveNow();
		SiqLog.setDiagnostics(config.diagnosticLogging);
		for (Runnable l : listeners) {
			l.run();
		}
	}

	public void resetToDefaults() {
		config = new SiqConfig();
		issues.clear();
		changed();
	}

	public List<String> issuesSnapshot() {
		return new ArrayList<>(issues);
	}
}
