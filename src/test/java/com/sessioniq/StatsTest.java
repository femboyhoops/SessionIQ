package com.sessioniq;

import com.sessioniq.analytics.DateRange;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.analytics.StatsEngine;
import com.sessioniq.analytics.TimelineBuilder;
import com.sessioniq.analytics.TimelineEvent;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.util.Ids;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {
	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	private static final long H = 3_600_000L;

	private static long at(int y, int m, int d, int h, int min) {
		return LocalDateTime.of(y, m, d, h, min).atZone(BERLIN).toInstant().toEpochMilli();
	}

	@Test
	void visitsAcrossMidnightAreSplitPerDay() {
		SessionRecord s = TestData.session(at(2026, 3, 9, 22, 0), 5 * H);
		TestData.visit(s, "a.example.com", 25565, at(2026, 3, 9, 23, 0), 2 * H);
		DateRange range = DateRange.custom(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10));
		StatsEngine.Stats st = StatsEngine.compute(List.of(s), range, BERLIN, false);
		assertEquals(H, st.daily.get(LocalDate.of(2026, 3, 9))[0]);
		assertEquals(H, st.daily.get(LocalDate.of(2026, 3, 10))[0]);
		assertEquals(2 * H, st.playtimeMs);
		assertEquals(5 * H, st.applicationMs);
		assertEquals(3 * H, st.idleMs);
	}

	@Test
	void dstChangeDoesNotDistortDailyTotals() {
		SessionRecord s = TestData.session(at(2026, 3, 28, 23, 0), 6 * H);
		TestData.visit(s, "a.example.com", 25565, at(2026, 3, 28, 23, 0), 6 * H);
		DateRange range = DateRange.custom(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29));
		StatsEngine.Stats st = StatsEngine.compute(List.of(s), range, BERLIN, false);
		assertEquals(H, st.daily.get(LocalDate.of(2026, 3, 28))[0]);
		assertEquals(5 * H, st.daily.get(LocalDate.of(2026, 3, 29))[0]);
		assertEquals(6 * H, st.playtimeMs, "elapsed time is preserved across the transition");
	}

	@Test
	void multipleServersAreNotDoubleCounted() {
		SessionRecord s = TestData.session(at(2026, 5, 1, 10, 0), 4 * H);
		TestData.visit(s, "a.example.com", 25565, at(2026, 5, 1, 10, 0), H);
		TestData.visit(s, "b.example.com", 25565, at(2026, 5, 1, 11, 0), H);
		TestData.visit(s, "a.example.com", 25565, at(2026, 5, 1, 12, 0), H);
		TestData.world(s, "World", at(2026, 5, 1, 13, 0), H / 2);
		StatsEngine.Stats st = StatsEngine.compute(List.of(s), DateRange.custom(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1)), BERLIN, false);
		assertEquals(3 * H + H / 2, st.playtimeMs);
		assertEquals(3 * H, st.multiplayerMs);
		assertEquals(H / 2, st.singleplayerMs);
		assertEquals(2, st.uniqueServers);
		assertEquals(3, st.multiplayerVisits);
		ServerAggregate a = st.servers.stream().filter(x -> x.key.equals("mp:a.example.com:25565")).findFirst().orElseThrow();
		assertEquals(2, a.visits);
		assertEquals(2 * H, a.totalMs);
		assertEquals("mp:a.example.com:25565", st.mostPlayedServer.key);
	}

	@Test
	void rangeClipsTimeAndCountsDisconnects() {
		SessionRecord s = TestData.session(at(2026, 6, 1, 23, 0), 2 * H);
		VisitRecord v = TestData.visit(s, "a.example.com", 25565, at(2026, 6, 1, 23, 0), 2 * H);
		DisconnectRecord d = new DisconnectRecord();
		d.id = Ids.disconnect();
		d.at = at(2026, 6, 2, 1, 0);
		d.visitId = v.id;
		d.serverKey = v.serverKey;
		d.category = DisconnectCategory.TIMEOUT.name();
		s.disconnects.add(d);
		StatsEngine.Stats day2 = StatsEngine.compute(List.of(s), DateRange.custom(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 2)), BERLIN, false);
		assertEquals(H, day2.playtimeMs);
		assertEquals(0, day2.sessions, "session started the day before");
		assertEquals(1, day2.unexpectedDisconnects);
	}

	@Test
	void histogramBuckets() {
		List<SessionRecord> list = new ArrayList<>();
		long base = at(2026, 7, 1, 8, 0);
		long[] durations = {5 * 60_000L, 20 * 60_000L, 45 * 60_000L, 90 * 60_000L, 3 * H, 5 * H};
		for (int i = 0; i < durations.length; i++) {
			list.add(TestData.session(base + i * 10 * H, durations[i]));
		}
		StatsEngine.Stats st = StatsEngine.compute(list, DateRange.of(DateRange.Preset.ALL, BERLIN), BERLIN, false);
		for (int b : st.durationHistogram) {
			assertEquals(1, b);
		}
		assertEquals(5 * H, st.longestSession.runtimeMs);
	}

	@Test
	void timelineGroupsChatBursts() {
		SessionRecord s = TestData.session(at(2026, 8, 1, 10, 0), 2 * H);
		VisitRecord v = TestData.visit(s, "a.example.com", 25565, at(2026, 8, 1, 10, 5), H);
		s.status = SessionRecord.STATUS_COMPLETED;
		List<ChatRecord> chat = new ArrayList<>();
		long t = v.joinedAt + 60_000;
		for (int i = 0; i < 10; i++) {
			chat.add(chat(s, v, t + i * 10_000));
		}
		for (int i = 0; i < 5; i++) {
			chat.add(chat(s, v, t + 20 * 60_000 + i * 5_000));
		}
		List<TimelineEvent> events = TimelineBuilder.build(s, chat, ms -> ms + "ms", k -> null);
		List<TimelineEvent> groups = events.stream().filter(e -> e.type == TimelineEvent.Type.CHAT).toList();
		assertEquals(2, groups.size());
		assertEquals(10, groups.get(0).count);
		assertEquals(5, groups.get(1).count);
		assertEquals(TimelineEvent.Type.SESSION_START, events.getFirst().type);
		assertEquals(TimelineEvent.Type.SESSION_END, events.getLast().type);
		for (int i = 1; i < events.size(); i++) {
			assertTrue(events.get(i).at >= events.get(i - 1).at);
		}
	}

	private static ChatRecord chat(SessionRecord s, VisitRecord v, long at) {
		ChatRecord r = new ChatRecord();
		r.id = Ids.chat();
		r.at = at;
		r.sessionId = s.id;
		r.visitId = v.id;
		r.serverKey = v.serverKey;
		r.text = "hi";
		r.origin = ChatRecord.ORIGIN_PLAYER;
		return r;
	}
}
