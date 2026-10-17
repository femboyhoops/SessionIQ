package com.sessioniq.model;

import com.sessioniq.util.Json;

public final class VisitRecord extends Json.Extensible {
	public static final String KIND_MULTIPLAYER = "multiplayer";
	public static final String KIND_LAN = "lan";
	public static final String KIND_REALMS = "realms";
	public static final String KIND_SINGLEPLAYER = "singleplayer";

	public static final String END_LEFT = "left";
	public static final String END_DISCONNECTED = "disconnected";
	public static final String END_GAME_CLOSED = "game_closed";
	public static final String END_RECORDING_STOPPED = "recording_stopped";
	public static final String END_INTERRUPTED = "interrupted";

	public String id;
	public String serverKey;
	public String kind;
	public String name;
	public String address;
	public String host;
	public int port;
	public long joinedAt;
	public Long leftAt;
	public long durationMs;
	public long pausedMs;
	public boolean open;
	public boolean interrupted;
	public boolean transfer;
	public String endReason;
	public String disconnectId;
	public int chatCount;
	public MetricSummary ping;

	public boolean isMultiplayer() {
		return !KIND_SINGLEPLAYER.equals(kind);
	}

	public long activeMs() {
		return Math.max(0, durationMs - pausedMs);
	}

	public long endEstimate() {
		return leftAt != null ? leftAt : joinedAt + durationMs;
	}
}
