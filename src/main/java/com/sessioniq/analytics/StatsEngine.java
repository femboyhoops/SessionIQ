package com.sessioniq.analytics;

import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StatsEngine {
	public static final long[] DURATION_BUCKETS_MIN = {15, 30, 60, 120, 240};
	public static final String[] DURATION_LABELS = {"<15m", "15-30m", "30-60m", "1-2h", "2-4h", "4h+"};
	private static final int MAX_DAYS = 3660;

	private StatsEngine() {
	}

	public static final class Stats {
		public DateRange range;
		public LocalDate firstDay;
		public LocalDate lastDay;
		public long playtimeMs;
		public long activeMs;
		public long multiplayerMs;
		public long singleplayerMs;
		public long applicationMs;
		public long idleMs;
		public int sessions;
		public int visits;
		public int multiplayerVisits;
		public int uniqueServers;
		public int chatMessages;
		public int disconnects;
		public int unexpectedDisconnects;
		public long averageSessionMs;
		public SessionRecord longestSession;
		public SessionRecord mostRecentSession;
		public ServerAggregate mostPlayedServer;
		public VisitRecord longestVisit;
		public String longestVisitSessionId;
		public final LinkedHashMap<LocalDate, long[]> daily = new LinkedHashMap<>();
		public final LinkedHashMap<LocalDate, long[]> weekly = new LinkedHashMap<>();
		public final LinkedHashMap<LocalDate, long[]> monthly = new LinkedHashMap<>();
		public final LinkedHashMap<LocalDate, int[]> disconnectsDaily = new LinkedHashMap<>();
		public final int[] durationHistogram = new int[DURATION_LABELS.length];
		public List<ServerAggregate> servers = new ArrayList<>();

		public boolean isEmpty() {
			return sessions == 0 && visits == 0 && disconnects == 0;
		}
	}

	public static Stats compute(List<SessionRecord> sessions, DateRange range, ZoneId zone, boolean mergePorts) {
		Stats st = new Stats();
		st.range = range;
		long rangeStart = range.startMillis(zone);
		long rangeEnd = range.endMillis(zone);

		LocalDate first = range.from();
		LocalDate last = range.to();
		if (first == null || last == null) {
			long min = Long.MAX_VALUE;
			for (SessionRecord s : sessions) {
				min = Math.min(min, s.startedAt);
			}
			LocalDate today = LocalDate.now(zone);
			first = min == Long.MAX_VALUE ? today : Instant.ofEpochMilli(min).atZone(zone).toLocalDate();
			last = today;
		}
		if (first.plusDays(MAX_DAYS).isBefore(last)) {
			first = last.minusDays(MAX_DAYS);
		}
		st.firstDay = first;
		st.lastDay = last;
		for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
			st.daily.put(d, new long[2]);
			st.disconnectsDaily.put(d, new int[2]);
		}

		long totalSessionMs = 0;
		java.util.Set<String> servers = new java.util.HashSet<>();
		for (SessionRecord s : sessions) {
			long sStart = s.startedAt;
			long sEnd = sStart + s.runtimeMs;
			boolean overlaps = sEnd > rangeStart && sStart < rangeEnd;
			if (overlaps) {
				st.applicationMs += clip(sStart, sEnd, rangeStart, rangeEnd);
			}
			if (sStart >= rangeStart && sStart < rangeEnd) {
				st.sessions++;
				totalSessionMs += s.runtimeMs;
				if (st.longestSession == null || s.runtimeMs > st.longestSession.runtimeMs) {
					st.longestSession = s;
				}
				if (st.mostRecentSession == null || s.startedAt > st.mostRecentSession.startedAt) {
					st.mostRecentSession = s;
				}
				st.durationHistogram[bucket(s.runtimeMs)]++;
			}
			for (VisitRecord v : s.visits) {
				long vStart = v.joinedAt;
				long vEnd = vStart + v.durationMs;
				long inRange = clip(vStart, vEnd, rangeStart, rangeEnd);
				if (inRange <= 0 && !(vStart >= rangeStart && vStart < rangeEnd)) {
					continue;
				}
				st.playtimeMs += inRange;
				st.activeMs += Math.min(inRange, v.activeMs());
				if (v.isMultiplayer()) {
					st.multiplayerMs += inRange;
				} else {
					st.singleplayerMs += inRange;
				}
				if (vStart >= rangeStart && vStart < rangeEnd) {
					st.visits++;
					st.chatMessages += v.chatCount;
					if (v.isMultiplayer()) {
						st.multiplayerVisits++;
						servers.add(com.sessioniq.track.ServerIdentity.groupKey(v, mergePorts));
					}
					if (st.longestVisit == null || v.durationMs > st.longestVisit.durationMs) {
						st.longestVisit = v;
						st.longestVisitSessionId = s.id;
					}
				}
				splitIntoDays(vStart, vEnd, zone, st.daily, v.isMultiplayer() ? 0 : 1);
			}
			for (DisconnectRecord d : s.disconnects) {
				if (d.at < rangeStart || d.at >= rangeEnd) {
					continue;
				}
				st.disconnects++;
				if (d.isUnexpected()) {
					st.unexpectedDisconnects++;
				}
				int[] day = st.disconnectsDaily.get(Instant.ofEpochMilli(d.at).atZone(zone).toLocalDate());
				if (day != null) {
					day[d.isUnexpected() ? 0 : 1]++;
				}
			}
		}
		st.uniqueServers = servers.size();
		st.idleMs = Math.max(0, st.applicationMs - st.playtimeMs);
		st.averageSessionMs = st.sessions == 0 ? 0 : totalSessionMs / st.sessions;

		for (Map.Entry<LocalDate, long[]> e : st.daily.entrySet()) {
			LocalDate week = e.getKey().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			LocalDate month = e.getKey().withDayOfMonth(1);
			long[] w = st.weekly.computeIfAbsent(week, k -> new long[2]);
			long[] m = st.monthly.computeIfAbsent(month, k -> new long[2]);
			w[0] += e.getValue()[0];
			w[1] += e.getValue()[1];
			m[0] += e.getValue()[0];
			m[1] += e.getValue()[1];
		}

		st.servers = ServerAggregate.compute(sessions, mergePorts, range, zone);
		st.servers.removeIf(a -> a.visits == 0);
		st.servers.sort(Comparator.comparingLong((ServerAggregate a) -> a.totalMs).reversed());
		for (ServerAggregate a : st.servers) {
			if (!a.isWorld()) {
				st.mostPlayedServer = a;
				break;
			}
		}
		return st;
	}

	static int bucket(long ms) {
		long min = ms / 60000;
		for (int i = 0; i < DURATION_BUCKETS_MIN.length; i++) {
			if (min < DURATION_BUCKETS_MIN[i]) {
				return i;
			}
		}
		return DURATION_BUCKETS_MIN.length;
	}

	private static long clip(long start, long end, long rangeStart, long rangeEnd) {
		return Math.max(0, Math.min(end, rangeEnd) - Math.max(start, rangeStart));
	}

	static void splitIntoDays(long start, long end, ZoneId zone, Map<LocalDate, long[]> days, int slot) {
		long cursor = start;
		int guard = 0;
		while (cursor < end && guard++ < MAX_DAYS) {
			ZonedDateTime z = Instant.ofEpochMilli(cursor).atZone(zone);
			LocalDate day = z.toLocalDate();
			long nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
			long segmentEnd = Math.min(end, nextMidnight);
			long[] bucket = days.get(day);
			if (bucket != null) {
				bucket[slot] += segmentEnd - cursor;
			}
			cursor = segmentEnd;
		}
	}
}
