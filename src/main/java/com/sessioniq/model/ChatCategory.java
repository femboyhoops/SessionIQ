package com.sessioniq.model;

public enum ChatCategory {
	CHAT("chat", "Chat"),
	WHISPER("whisper", "Whisper"),
	TEAM("team", "Team"),
	EMOTE("emote", "Emote"),
	ANNOUNCEMENT("announcement", "Announcement"),
	PLAYER_OTHER("player_other", "Other player message"),
	SYSTEM("system", "System");

	public final String id;
	public final String label;

	ChatCategory(String id, String label) {
		this.id = id;
		this.label = label;
	}

	public static ChatCategory parse(String id) {
		for (ChatCategory c : values()) {
			if (c.id.equals(id)) {
				return c;
			}
		}
		return PLAYER_OTHER;
	}
}
