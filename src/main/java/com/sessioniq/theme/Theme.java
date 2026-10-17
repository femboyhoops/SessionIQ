package com.sessioniq.theme;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

public final class Theme {
	public static final int CHART_COLORS = 6;

	public String name;
	public transient boolean builtIn;
	public boolean dark = true;
	public int bg;
	public int bg2;
	public int surface;
	public int surface2;
	public int border;
	public int text;
	public int text2;
	public int accent;
	public int success;
	public int warning;
	public int error;
	public int[] chart = new int[CHART_COLORS];
	public int radius = 3;
	public float panelOpacity = 1f;
	public float backdropOpacity = 0.94f;

	public Theme copy(String newName) {
		Theme t = new Theme();
		t.name = newName;
		t.dark = dark;
		t.bg = bg;
		t.bg2 = bg2;
		t.surface = surface;
		t.surface2 = surface2;
		t.border = border;
		t.text = text;
		t.text2 = text2;
		t.accent = accent;
		t.success = success;
		t.warning = warning;
		t.error = error;
		t.chart = chart.clone();
		t.radius = radius;
		t.panelOpacity = panelOpacity;
		t.backdropOpacity = backdropOpacity;
		return t;
	}

	public enum Slot {
		BG("Background"), BG2("Secondary background"), SURFACE("Panel surface"), SURFACE2("Raised surface"), BORDER("Border"),
		TEXT("Primary text"), TEXT2("Secondary text"), ACCENT("Accent"), SUCCESS("Success"), WARNING("Warning"), ERROR("Error"),
		CHART1("Chart 1"), CHART2("Chart 2"), CHART3("Chart 3"), CHART4("Chart 4"), CHART5("Chart 5"), CHART6("Chart 6");

		public final String label;

		Slot(String label) {
			this.label = label;
		}
	}

	public int get(Slot s) {
		return switch (s) {
			case BG -> bg;
			case BG2 -> bg2;
			case SURFACE -> surface;
			case SURFACE2 -> surface2;
			case BORDER -> border;
			case TEXT -> text;
			case TEXT2 -> text2;
			case ACCENT -> accent;
			case SUCCESS -> success;
			case WARNING -> warning;
			case ERROR -> error;
			default -> chart[s.ordinal() - Slot.CHART1.ordinal()];
		};
	}

	public void set(Slot s, int rgb) {
		rgb &= 0xFFFFFF;
		switch (s) {
			case BG -> bg = rgb;
			case BG2 -> bg2 = rgb;
			case SURFACE -> surface = rgb;
			case SURFACE2 -> surface2 = rgb;
			case BORDER -> border = rgb;
			case TEXT -> text = rgb;
			case TEXT2 -> text2 = rgb;
			case ACCENT -> accent = rgb;
			case SUCCESS -> success = rgb;
			case WARNING -> warning = rgb;
			case ERROR -> error = rgb;
			default -> chart[s.ordinal() - Slot.CHART1.ordinal()] = rgb;
		}
	}

	public static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	public static Integer parseHex(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		if (t.length() == 3) {
			t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
		}
		if (t.length() != 6) {
			return null;
		}
		try {
			return Integer.parseInt(t, 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("dark", dark);
		for (Slot s : Slot.values()) {
			if (s.ordinal() < Slot.CHART1.ordinal()) {
				o.addProperty(key(s), hex(get(s)));
			}
		}
		JsonArray c = new JsonArray();
		for (int v : chart) {
			c.add(hex(v));
		}
		o.add("chart", c);
		o.addProperty("radius", radius);
		o.addProperty("panelOpacity", panelOpacity);
		o.addProperty("backdropOpacity", backdropOpacity);
		return o;
	}

	public static Theme fromJson(JsonObject o, Theme base, List<String> problems) {
		String name = o.has("name") ? o.get("name").getAsString() : null;
		if (name == null || name.isBlank()) {
			problems.add("A custom theme without a name was skipped");
			return null;
		}
		Theme t = base.copy(name.trim());
		if (o.has("dark")) {
			try {
				t.dark = o.get("dark").getAsBoolean();
			} catch (Exception e) {
				problems.add("Theme '" + name + "': invalid 'dark' value");
			}
		}
		for (Slot s : Slot.values()) {
			if (s.ordinal() >= Slot.CHART1.ordinal()) {
				continue;
			}
			if (o.has(key(s))) {
				Integer v = o.get(key(s)).isJsonPrimitive() ? parseHex(o.get(key(s)).getAsString()) : null;
				if (v == null) {
					problems.add("Theme '" + name + "': invalid colour for " + key(s));
				} else {
					t.set(s, v);
				}
			}
		}
		if (o.has("chart") && o.get("chart").isJsonArray()) {
			JsonArray c = o.getAsJsonArray("chart");
			for (int i = 0; i < Math.min(CHART_COLORS, c.size()); i++) {
				JsonElement e = c.get(i);
				Integer v = e.isJsonPrimitive() ? parseHex(e.getAsString()) : null;
				if (v != null) {
					t.chart[i] = v;
				}
			}
		}
		try {
			if (o.has("radius")) {
				t.radius = Math.max(0, Math.min(8, o.get("radius").getAsInt()));
			}
			if (o.has("panelOpacity")) {
				t.panelOpacity = Math.max(0.6f, Math.min(1f, o.get("panelOpacity").getAsFloat()));
			}
			if (o.has("backdropOpacity")) {
				t.backdropOpacity = Math.max(0.5f, Math.min(1f, o.get("backdropOpacity").getAsFloat()));
			}
		} catch (Exception e) {
			problems.add("Theme '" + name + "': invalid shape value");
		}
		return t;
	}

	private static String key(Slot s) {
		return switch (s) {
			case BG -> "background";
			case BG2 -> "background2";
			case SURFACE -> "surface";
			case SURFACE2 -> "surface2";
			case BORDER -> "border";
			case TEXT -> "text";
			case TEXT2 -> "text2";
			case ACCENT -> "accent";
			case SUCCESS -> "success";
			case WARNING -> "warning";
			case ERROR -> "error";
			default -> "chart";
		};
	}
}
