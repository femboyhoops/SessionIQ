package com.sessioniq.online;

import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The all-time summary that is uploaded for Global Statistics and friends. This class is the
 * complete list of what leaves the computer: totals plus playtime and joins per public server.
 * Chat, disconnects, timelines, world names and private or LAN addresses are never included.
 */
public final class StatsSnapshot {
	public static final int MAX_SERVERS = 15;

	public long playtimeMs;
	public int sessions;
	public int totalJoins;
	public int serversJoined;
	public String topServer;
	public long topServerMs;
	public List<Server> servers = new ArrayList<>();

	public static final class Server {
		public String host;
		public long playtimeMs;
		public int joins;

		Server(String host, long playtimeMs, int joins) {
			this.host = host;
			this.playtimeMs = playtimeMs;
			this.joins = joins;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Server s && s.host.equals(host) && s.playtimeMs == playtimeMs && s.joins == joins;
		}

		@Override
		public int hashCode() {
			return Objects.hash(host, playtimeMs, joins);
		}
	}

	public static StatsSnapshot compute(List<SessionRecord> history, boolean includeServers) {
		StatsSnapshot s = new StatsSnapshot();
		Map<String, Server> byHost = new HashMap<>();
		for (SessionRecord session : history) {
			s.sessions++;
			for (VisitRecord v : session.visits) {
				s.playtimeMs += Math.max(0, v.durationMs);
				if (!v.isMultiplayer()) {
					continue;
				}
				s.totalJoins++;
				String host = VisitRecord.KIND_MULTIPLAYER.equals(v.kind) ? PublicHost.of(v.host) : null;
				if (host == null) {
					continue;
				}
				Server server = byHost.computeIfAbsent(host, h -> new Server(h, 0, 0));
				server.playtimeMs += Math.max(0, v.durationMs);
				server.joins++;
			}
		}
		List<Server> sorted = new ArrayList<>(byHost.values());
		sorted.sort((a, b) -> a.playtimeMs != b.playtimeMs ? Long.compare(b.playtimeMs, a.playtimeMs) : a.host.compareTo(b.host));
		s.serversJoined = sorted.size();
		if (!sorted.isEmpty()) {
			s.topServer = sorted.getFirst().host;
			s.topServerMs = sorted.getFirst().playtimeMs;
		}
		if (includeServers) {
			s.servers = new ArrayList<>(sorted.subList(0, Math.min(MAX_SERVERS, sorted.size())));
		}
		return s;
	}

	/** Totals rounded to the minute, so the snapshot only changes (and is only re-sent) when something meaningful changed. */
	public String fingerprint() {
		StringBuilder b = new StringBuilder();
		b.append(playtimeMs / 60_000).append('|').append(sessions).append('|').append(totalJoins).append('|').append(serversJoined).append('|').append(topServer);
		for (Server server : servers) {
			b.append('|').append(server.host).append(':').append(server.playtimeMs / 60_000).append(':').append(server.joins);
		}
		return b.toString();
	}
}
