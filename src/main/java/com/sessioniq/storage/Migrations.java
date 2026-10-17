package com.sessioniq.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class Migrations {
	public static final int CURRENT = 1;

	public interface Step {
		int from();

		boolean destructive();

		String description();

		void apply(DataPaths paths) throws IOException;
	}

	static final List<Step> STEPS = new ArrayList<>();

	public record Status(int schema, boolean newerThanSupported, List<String> applied, String problem) {
	}

	public interface BackupHook {
		String backup(String label) throws IOException;
	}

	public static Status ensure(DataPaths paths, String modVersion, BackupHook backup) {
		JsonObject manifest = null;
		String problem = null;
		if (Files.exists(paths.manifest)) {
			try {
				manifest = JsonParser.parseString(Files.readString(paths.manifest, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception e) {
				problem = "manifest.json could not be read; it was rebuilt (a copy is in quarantine)";
				try {
					AtomicFiles.quarantine(paths.manifest, paths.quarantine);
				} catch (IOException ignored) {
					problem = "manifest.json could not be read or copied aside; it was rebuilt";
				}
			}
		}
		long now = System.currentTimeMillis();
		if (manifest == null) {
			manifest = new JsonObject();
			manifest.addProperty("schema", CURRENT);
			manifest.addProperty("createdAt", now);
			manifest.addProperty("createdBy", modVersion);
			manifest.add("history", new JsonArray());
		}
		int schema = manifest.has("schema") ? manifest.get("schema").getAsInt() : CURRENT;
		List<String> applied = new ArrayList<>();
		if (schema > CURRENT) {
			manifest.addProperty("lastOpenedAt", now);
			manifest.addProperty("lastOpenedBy", modVersion);
			write(paths, manifest);
			return new Status(schema, true, applied, problem);
		}
		while (schema < CURRENT) {
			int from = schema;
			Step step = STEPS.stream().filter(s -> s.from() == from).findFirst().orElse(null);
			if (step == null) {
				problem = "No migration from schema " + schema + " is available; data was left unchanged";
				break;
			}
			try {
				String backupName = step.destructive() ? backup.backup("pre-migration-v" + from) : null;
				step.apply(paths);
				JsonObject entry = new JsonObject();
				entry.addProperty("from", from);
				entry.addProperty("to", from + 1);
				entry.addProperty("at", now);
				entry.addProperty("description", step.description());
				if (backupName != null) {
					entry.addProperty("backup", backupName);
				}
				manifest.getAsJsonArray("history").add(entry);
				schema = from + 1;
				manifest.addProperty("schema", schema);
				applied.add(step.description());
			} catch (IOException e) {
				problem = "Migration from schema " + from + " failed: " + e.getMessage() + ". Data was left at schema " + from;
				break;
			}
		}
		manifest.addProperty("lastOpenedAt", now);
		manifest.addProperty("lastOpenedBy", modVersion);
		write(paths, manifest);
		return new Status(schema, false, applied, problem);
	}

	private static void write(DataPaths paths, JsonObject manifest) {
		try {
			AtomicFiles.writeString(paths.manifest, Json.PRETTY.toJson(manifest));
		} catch (IOException ignored) {
		}
	}
}
