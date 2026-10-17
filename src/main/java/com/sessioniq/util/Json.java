package com.sessioniq.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Json {
	public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
	public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

	private static final Map<Class<?>, Set<String>> KNOWN_FIELDS = new ConcurrentHashMap<>();

	private Json() {
	}

	public static <T> T read(String json, Class<T> type) {
		JsonElement tree = JsonParser.parseString(json);
		if (!tree.isJsonObject()) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		return fromTree(tree.getAsJsonObject(), type);
	}

	public static <T> T fromTree(JsonObject object, Class<T> type) {
		T value = COMPACT.fromJson(object, type);
		if (value == null) {
			throw new IllegalArgumentException("Empty JSON value");
		}
		if (value instanceof Extensible extensible) {
			Set<String> known = knownFields(type);
			JsonObject unknown = null;
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (!known.contains(entry.getKey())) {
					if (unknown == null) {
						unknown = new JsonObject();
					}
					unknown.add(entry.getKey(), entry.getValue());
				}
			}
			extensible.unknownFields = unknown;
		}
		return value;
	}

	public static String write(Object value, boolean pretty) {
		Gson gson = pretty ? PRETTY : COMPACT;
		if (value instanceof Extensible extensible && extensible.unknownFields != null && !extensible.unknownFields.isEmpty()) {
			JsonObject tree = gson.toJsonTree(value).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : extensible.unknownFields.entrySet()) {
				if (!tree.has(entry.getKey())) {
					tree.add(entry.getKey(), entry.getValue());
				}
			}
			return gson.toJson(tree);
		}
		return gson.toJson(value);
	}

	public static <T> T copy(T value, Class<T> type) {
		return read(write(value, false), type);
	}

	private static Set<String> knownFields(Class<?> type) {
		return KNOWN_FIELDS.computeIfAbsent(type, t -> {
			Set<String> names = new HashSet<>();
			for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Field field : c.getDeclaredFields()) {
					int mods = field.getModifiers();
					if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
						continue;
					}
					SerializedName serialized = field.getAnnotation(SerializedName.class);
					if (serialized != null) {
						names.add(serialized.value());
						names.addAll(java.util.List.of(serialized.alternate()));
					} else {
						names.add(field.getName());
					}
				}
			}
			return names;
		});
	}

	public abstract static class Extensible {
		public transient JsonObject unknownFields;
	}
}
