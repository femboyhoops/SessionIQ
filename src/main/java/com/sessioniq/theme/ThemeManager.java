package com.sessioniq.theme;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ThemeManager {
	private final Path file;
	private final Path quarantine;
	private final List<Theme> builtIns = ThemePresets.all();
	private final List<Theme> custom = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();
	private Theme active;

	public ThemeManager(Path file, Path quarantine) {
		this.file = file;
		this.quarantine = quarantine;
	}

	public void load(String activeName) {
		custom.clear();
		problems.clear();
		if (Files.exists(file)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray arr = root.has("custom") ? root.getAsJsonArray("custom") : new JsonArray();
				for (JsonElement e : arr) {
					if (!e.isJsonObject()) {
						continue;
					}
					Theme t = Theme.fromJson(e.getAsJsonObject(), builtIns.getFirst(), problems);
					if (t != null && isBuiltInName(t.name)) {
						String base = t.name + " (custom)";
						String n = base;
						for (int i = 2; find(n) != null; i++) {
							n = base + " " + i;
						}
						t.name = n;
					}
					if (t != null && find(t.name) == null) {
						custom.add(t);
					}
				}
			} catch (Exception e) {
				try {
					AtomicFiles.quarantine(file, quarantine);
					problems.add("themes.json could not be read; a copy was preserved in quarantine and built-in themes are available");
				} catch (IOException io) {
					problems.add("themes.json could not be read: " + e.getMessage());
				}
			}
		}
		active = find(activeName);
		if (active == null) {
			active = builtIns.getFirst();
		}
	}

	public Theme active() {
		return active;
	}

	public void preview(Theme t) {
		active = t;
	}

	public void select(String name) {
		Theme t = find(name);
		if (t != null) {
			active = t;
		}
	}

	public List<Theme> builtIns() {
		return builtIns;
	}

	public List<Theme> custom() {
		return custom;
	}

	public List<String> problems() {
		return problems;
	}

	public Theme find(String name) {
		if (name == null) {
			return null;
		}
		for (Theme t : builtIns) {
			if (t.name.equalsIgnoreCase(name)) {
				return t;
			}
		}
		for (Theme t : custom) {
			if (t.name.equalsIgnoreCase(name)) {
				return t;
			}
		}
		return null;
	}

	public boolean isBuiltInName(String name) {
		return builtIns.stream().anyMatch(t -> t.name.equalsIgnoreCase(name));
	}

	public String saveCustom(Theme theme) {
		if (theme.name == null || theme.name.isBlank()) {
			return "Enter a name for the theme";
		}
		theme.name = theme.name.trim();
		if (theme.name.length() > 32) {
			return "Theme names are limited to 32 characters";
		}
		if (isBuiltInName(theme.name)) {
			return "\"" + theme.name + "\" is a built-in theme; choose another name";
		}
		theme.builtIn = false;
		custom.removeIf(t -> t.name.equalsIgnoreCase(theme.name));
		custom.add(theme);
		return write();
	}

	public String deleteCustom(String name) {
		if (isBuiltInName(name)) {
			return "Built-in themes cannot be deleted";
		}
		boolean removed = custom.removeIf(t -> t.name.equalsIgnoreCase(name));
		if (!removed) {
			return "No custom theme named " + name;
		}
		if (active != null && active.name.equalsIgnoreCase(name)) {
			active = builtIns.getFirst();
		}
		return write();
	}

	private String write() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		JsonArray arr = new JsonArray();
		for (Theme t : custom) {
			arr.add(t.toJson());
		}
		root.add("custom", arr);
		try {
			AtomicFiles.writeString(file, Json.PRETTY.toJson(root));
			return null;
		} catch (IOException e) {
			return "Could not save themes.json: " + e.getMessage();
		}
	}
}
