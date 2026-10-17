package com.sessioniq.analytics;

import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.track.ServerIdentity;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerAggregate {
	public String key;
	public String kind;
	public String name;
	public String address;
	public String host;
	public int port;
	public long totalMs;
	public long activeMs;
	public int visits;
	public long longestMs;
	public long firstSeen = Long.MAX_VALUE;
	public long lastPlayed;
	public int disconnects;
	public int unexpectedDisconnects;
	public String lastDisconnectReason;
	public long lastDisconnectAt;
	public int chatMessages;
	public final Set<String> names = new LinkedHashSet<>();
	public final Set<Integer> ports = new LinkedHashSet<>();

	public boolean isWorld() {
		return VisitRecord.KIND_SINGLEPLAYER.equals(kind);
	}

	public static List<ServerAggregate> compute(List<SessionRecord> sessions, boolean mergePorts, DateRange range, ZoneId zone) {
		Map<String, ServerAggregate> map = new HashMap<>();
		for (SessionRecord s : sessions) {
			for (VisitRecord v : s.visits) {
				long start = v.joinedAt;
				long end = start + v.durationMs;
				if (range != null && !range.isAll() && !range.overlaps(start, end, zone)) {
					continue;
				}
				String key = ServerIdentity.groupKey(v, mergePorts);
				ServerAggregate a = map.computeIfAbsent(key, k -> {
					ServerAggregate n = new ServerAggregate();
					n.key = k;
					n.kind = v.kind;
					return n;
				});
				long ms = range == null || range.isAll() ? v.durationMs : range.clip(start, end, zone);
				a.totalMs += ms;
				a.activeMs += range == null || range.isAll() ? v.activeMs() : Math.min(ms, v.activeMs());
				a.visits++;
				a.longestMs = Math.max(a.longestMs, v.durationMs);
				a.firstSeen = Math.min(a.firstSeen, v.joinedAt);
				a.chatMessages += v.chatCount;
				if (v.joinedAt >= a.lastPlayed) {
					a.lastPlayed = v.joinedAt;
					a.name = v.name;
					a.address = v.address;
					a.host = v.host;
					a.port = v.port;
					a.kind = v.kind;
				}
				if (v.name != null) {
					a.names.add(v.name);
				}
				if (v.port > 0) {
					a.ports.add(v.port);
				}
			}
			for (DisconnectRecord d : s.disconnects) {
				if (d.serverKey == null || range != null && !range.isAll() && !range.contains(d.at, zone)) {
					continue;
				}
				String key = ServerIdentity.groupKey(d.serverKey, mergePorts);
				ServerAggregate a = map.get(key);
				if (a == null) {
					a = new ServerAggregate();
					a.key = key;
					a.kind = key.startsWith("realms:") ? VisitRecord.KIND_REALMS : VisitRecord.KIND_MULTIPLAYER;
					a.name = d.serverName;
					a.address = d.address;
					map.put(key, a);
				}
				a.disconnects++;
				if (d.isUnexpected()) {
					a.unexpectedDisconnects++;
				}
				if (d.at >= a.lastDisconnectAt) {
					a.lastDisconnectAt = d.at;
					a.lastDisconnectReason = d.reason;
				}
			}
		}
		List<ServerAggregate> out = new ArrayList<>(map.values());
		for (ServerAggregate a : out) {
			if (a.firstSeen == Long.MAX_VALUE) {
				a.firstSeen = a.lastDisconnectAt;
			}
			if (a.name == null) {
				a.name = a.host != null ? a.host : a.key;
			}
		}
		return out;
	}
}
