package com.sessioniq.online;

import java.util.List;
import java.util.Map;

/** Response shapes of the SessionIQ online service (backend/src). */
public final class Api {
	private Api() {
	}

	public static final class Challenge {
		public String nonce;
	}

	public static final class Verified {
		public String uuid;
		public String name;
		public String token;
		public long expiresAt;
		public boolean admin;
	}

	public static final class Privacy {
		public boolean shareStats;
		public boolean showOnline;
		public boolean showServer;
		public boolean showStats;
		public boolean allowChat;
	}

	public static final class Profile {
		public String uuid;
		public String name;
		public boolean admin;
		public Privacy privacy;
	}

	public static final class Presence {
		public Map<String, Integer> unread = Map.of();
		public int requests;
	}

	public static final class ServerRow {
		public String host;
		public long playtimeMs;
		public long joins;
		public int players;
	}

	public static final class PlayerRow {
		public String name;
		public long playtimeMs;
		public int sessions;
		public int serversJoined;
		public String topServer;
	}

	public static final class Totals {
		public long playtimeMs;
		public int players;
		public int servers;
		public long sessions;
	}

	public static final class Rank {
		public int rank;
		public long playtimeMs;
	}

	public static final class Global {
		public long generatedAt;
		public Totals totals = new Totals();
		public List<ServerRow> topServersByPlaytime = List.of();
		public List<ServerRow> topServersByJoins = List.of();
		public List<PlayerRow> topPlayers = List.of();
		public Rank you;
	}

	public static final class FriendStats {
		public long playtimeMs;
		public int sessions;
		public int serversJoined;
		public int totalJoins;
		public String topServer;
		public long topServerMs;
		public long updatedAt;
	}

	public static final class Friend {
		public String uuid;
		public String name;
		public long since;
		/** online, offline or hidden */
		public String status;
		public Long lastSeen;
		/** server:host, private, singleplayer, or null when not shared or offline */
		public String activity;
		public FriendStats stats;
		public boolean canChat;
		public boolean theyAllowChat;
		public int unread;

		public boolean online() {
			return "online".equals(status);
		}
	}

	public static final class PlayerRef {
		public String uuid;
		public String name;
		public long at;
	}

	public static final class Friends {
		public List<Friend> friends = List.of();
		public List<PlayerRef> incoming = List.of();
		public List<PlayerRef> outgoing = List.of();
		public List<PlayerRef> blocked = List.of();
	}

	public static final class RequestResult {
		/** sent or friends */
		public String status;
		public String name;
	}

	public static final class Message {
		public long id;
		public boolean mine;
		public String text;
		public long at;
	}

	public static final class Conversation {
		public List<Message> messages = List.of();
		public int retentionDays;
	}

	public static final class Daily {
		public String day;
		public long launches;
		public long active;
		public long newInstalls;
	}

	public static final class VersionCount {
		public String version;
		public long installs;
	}

	public static final class AdminOverview {
		public long onlineNow;
		public long installs;
		public long launches;
		public long active1d;
		public long active7d;
		public long active30d;
		public long players;
		public long playersActive7d;
		public long newSuggestions;
		public List<Daily> daily = List.of();
		public List<VersionCount> versions = List.of();
	}

	public static final class Suggestion {
		public long id;
		public long at;
		public String player;
		public String category;
		public String text;
		public String modVersion;
		public String status;
	}

	public static final class Suggestions {
		public List<Suggestion> suggestions = List.of();
	}
}
