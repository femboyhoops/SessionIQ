package com.sessioniq.config;

import com.sessioniq.util.Json;

public final class SiqConfig extends Json.Extensible {
	public int schema = 1;

	public String defaultPage = "overview";
	public boolean clock24h = true;
	public String dateFormat = "YMD";
	public String timeZone = "system";
	public boolean showTimeZone = false;

	public boolean trackSessions = true;
	public boolean trackMultiplayer = true;
	public boolean trackSingleplayer = true;
	public boolean recordChat = true;
	public boolean recordSystemMessages = true;
	public boolean recordChatFormatting = true;
	public boolean recordDeaths = true;
	public boolean recordPing = true;
	public int pingIntervalSec = 10;
	public boolean recordMemory = true;
	public int memoryIntervalSec = 30;

	public boolean showAddresses = true;
	public boolean mergePorts = false;

	public String theme = "Obsidian";
	public float uiScale = 1.0f;
	public float fontScale = 1.0f;
	public boolean compact = false;
	public float animation = 1.0f;
	public boolean chatDetailed = false;

	public boolean autoBackup = true;
	public int backupIntervalHours = 24;
	public int maxBackups = 10;
	public String exportDir = "";

	public boolean diagnosticLogging = false;
	public int checkpointSeconds = 30;

	public static final String[] PAGES = {"overview", "sessions", "servers", "chat", "disconnects", "timeline", "statistics", "settings"};
	public static final int[] BACKUP_INTERVALS = {6, 12, 24, 48, 168};

	public java.util.List<String> validate() {
		java.util.List<String> fixes = new java.util.ArrayList<>();
		if (!java.util.Arrays.asList(PAGES).contains(defaultPage)) {
			fixes.add("defaultPage '" + defaultPage + "' is not a page; using overview");
			defaultPage = "overview";
		}
		if (!"YMD".equals(dateFormat) && !"DMY".equals(dateFormat) && !"MDY".equals(dateFormat)) {
			fixes.add("dateFormat '" + dateFormat + "' is invalid; using YMD");
			dateFormat = "YMD";
		}
		if (timeZone == null || !"system".equals(timeZone)) {
			try {
				java.time.ZoneId.of(timeZone);
			} catch (Exception e) {
				fixes.add("timeZone '" + timeZone + "' is not a valid zone; using system");
				timeZone = "system";
			}
		}
		pingIntervalSec = clamp(fixes, "pingIntervalSec", pingIntervalSec, 2, 300, 10);
		memoryIntervalSec = clamp(fixes, "memoryIntervalSec", memoryIntervalSec, 5, 600, 30);
		backupIntervalHours = clamp(fixes, "backupIntervalHours", backupIntervalHours, 1, 24 * 30, 24);
		maxBackups = clamp(fixes, "maxBackups", maxBackups, 1, 100, 10);
		checkpointSeconds = clamp(fixes, "checkpointSeconds", checkpointSeconds, 10, 300, 30);
		uiScale = clampF(fixes, "uiScale", uiScale, 0.75f, 1.5f, 1.0f);
		fontScale = clampF(fixes, "fontScale", fontScale, 0.75f, 1.5f, 1.0f);
		animation = clampF(fixes, "animation", animation, 0f, 1f, 1.0f);
		if (theme == null || theme.isBlank()) {
			fixes.add("theme was empty; using Obsidian");
			theme = "Obsidian";
		}
		if (exportDir == null) {
			exportDir = "";
		}
		return fixes;
	}

	private static int clamp(java.util.List<String> fixes, String name, int value, int min, int max, int def) {
		if (value < min || value > max) {
			fixes.add(name + " " + value + " is outside " + min + ".." + max + "; using " + def);
			return def;
		}
		return value;
	}

	private static float clampF(java.util.List<String> fixes, String name, float value, float min, float max, float def) {
		if (Float.isNaN(value) || value < min || value > max) {
			fixes.add(name + " " + value + " is outside " + min + ".." + max + "; using " + def);
			return def;
		}
		return value;
	}
}
