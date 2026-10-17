package com.sessioniq.model;

import com.sessioniq.util.Json;

import java.util.ArrayList;
import java.util.List;

public final class SessionRecord extends Json.Extensible {
	public static final int SCHEMA = 1;

	public static final String STATUS_ACTIVE = "ACTIVE";
	public static final String STATUS_COMPLETED = "COMPLETED";
	public static final String STATUS_INTERRUPTED = "INTERRUPTED";

	public static final String TYPE_MULTIPLAYER = "multiplayer";
	public static final String TYPE_SINGLEPLAYER = "singleplayer";
	public static final String TYPE_MIXED = "mixed";
	public static final String TYPE_MENU = "menu";

	public int schema = SCHEMA;
	public String id;
	public long startedAt;
	public Long endedAt;
	public long lastCheckpointAt;
	public long runtimeMs;
	public long inWorldMs;
	public long activeMs;
	public long multiplayerMs;
	public long singleplayerMs;
	public String mcVersion;
	public String modVersion;
	public String status = STATUS_ACTIVE;
	public String type = TYPE_MENU;
	public boolean recovered;
	public Long recoveredAt;
	public String recoveryNote;
	public String endNote;
	public List<VisitRecord> visits = new ArrayList<>();
	public List<DisconnectRecord> disconnects = new ArrayList<>();
	public List<MarkerRecord> markers = new ArrayList<>();
	public int chatCount;
	public int connectionAttempts;
	public int failedConnections;
	public int reconnectAttempts;
	public MetricSummary memoryUsedMb;
	public Long memoryMaxMb;

	public boolean isActive() {
		return STATUS_ACTIVE.equals(status);
	}

	public long endEstimate() {
		return endedAt != null ? endedAt : startedAt + runtimeMs;
	}

	public String computeType() {
		boolean mp = false;
		boolean sp = false;
		for (VisitRecord v : visits) {
			if (v.isMultiplayer()) {
				mp = true;
			} else {
				sp = true;
			}
		}
		return mp && sp ? TYPE_MIXED : mp ? TYPE_MULTIPLAYER : sp ? TYPE_SINGLEPLAYER : TYPE_MENU;
	}

	public long uniqueServers() {
		return visits.stream().filter(VisitRecord::isMultiplayer).map(v -> v.serverKey).distinct().count();
	}

	public long multiplayerVisits() {
		return visits.stream().filter(VisitRecord::isMultiplayer).count();
	}

	public long unexpectedDisconnects() {
		return disconnects.stream().filter(DisconnectRecord::isUnexpected).count();
	}

	public VisitRecord visit(String visitId) {
		if (visitId == null) {
			return null;
		}
		for (VisitRecord v : visits) {
			if (visitId.equals(v.id)) {
				return v;
			}
		}
		return null;
	}

	public DisconnectRecord disconnect(String disconnectId) {
		for (DisconnectRecord d : disconnects) {
			if (d.id.equals(disconnectId)) {
				return d;
			}
		}
		return null;
	}

	public void recomputeTotals() {
		long inWorld = 0;
		long active = 0;
		long mp = 0;
		long sp = 0;
		for (VisitRecord v : visits) {
			inWorld += v.durationMs;
			active += v.activeMs();
			if (v.isMultiplayer()) {
				mp += v.durationMs;
			} else {
				sp += v.durationMs;
			}
		}
		inWorldMs = inWorld;
		activeMs = active;
		multiplayerMs = mp;
		singleplayerMs = sp;
		type = computeType();
	}
}
