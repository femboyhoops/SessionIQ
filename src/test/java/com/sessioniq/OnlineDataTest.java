package com.sessioniq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.online.ApiClient;
import com.sessioniq.online.PublicHost;
import com.sessioniq.online.StatsSnapshot;
import com.sessioniq.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineDataTest {
	private static final long T0 = 1_760_000_000_000L;

	@Test
	void privateAddressesNeverQualify() {
		for (String host : List.of("localhost", "127.0.0.1", "192.168.1.20", "10.0.0.5:25565", "::1", "[::1]:25565", "fe80::1", "myserver", "box.local",
			"router.lan", "nas.home", "mc.internal", "x.test", "server.localdomain", "1.2.3.4", "", "a.b", "-bad.example.net", "space .net")) {
			assertNull(PublicHost.of(host), host);
		}
		assertNull(PublicHost.of(null));
	}

	@Test
	void publicHostsAreNormalised() {
		assertEquals("hypixel.net", PublicHost.of("mc.hypixel.net"));
		assertEquals("hypixel.net", PublicHost.of("Play.Hypixel.NET:25565"));
		assertEquals("hypixel.net", PublicHost.of("hypixel.net."));
		assertEquals("play.net", PublicHost.of("play.net"));
	}

	@Test
	void subAddressesAreGroupedUnderTheirMainDomain() {
		assertEquals("mcpvp.com", PublicHost.of("londs.mcpvp.com"));
		assertEquals("mcpvp.com", PublicHost.of("fra.mcpvp.com:25565"));
		assertEquals("mineplex.com", PublicHost.of("us.lobby.mineplex.com"));
		assertEquals("example-smp.co.uk", PublicHost.of("mc.example-smp.co.uk"), "two-label country suffixes keep the real name");
		assertEquals("example.com.au", PublicHost.of("play.example.com.au"));
		assertEquals("mycoolsmp.aternos.me", PublicHost.of("mycoolsmp.aternos.me"), "every Aternos subdomain is its own server");
		assertEquals("lobby.minehut.gg", PublicHost.of("lobby.minehut.gg"));
		assertEquals("myserver.my.pebble.host", PublicHost.of("eu.myserver.my.pebble.host"));
		assertEquals("minehut.gg", PublicHost.of("minehut.gg"));
	}

	@Test
	void snapshotContainsOnlyTotalsAndPublicServers() {
		SessionRecord s1 = TestData.session(T0, 10_000_000);
		TestData.visit(s1, "mc.hypixel.net", 25565, T0, 3_600_000);
		TestData.visit(s1, "192.168.1.20", 25565, T0 + 3_600_000, 1_000_000);
		TestData.world(s1, "My Secret World", T0 + 4_600_000, 500_000);
		VisitRecord lan = TestData.visit(s1, "friend-pc", 51234, T0 + 5_100_000, 200_000);
		lan.kind = VisitRecord.KIND_LAN;
		SessionRecord s2 = TestData.session(T0 + 86_400_000, 5_000_000);
		TestData.visit(s2, "play.hypixel.net", 25565, T0 + 86_400_000, 1_400_000);
		TestData.visit(s2, "smp.example.org", 25565, T0 + 87_800_000, 600_000);

		StatsSnapshot snap = StatsSnapshot.compute(List.of(s1, s2), true);
		assertEquals(2, snap.sessions);
		assertEquals(3_600_000 + 1_000_000 + 500_000 + 200_000 + 1_400_000 + 600_000, snap.playtimeMs);
		assertEquals(5, snap.totalJoins, "every multiplayer, LAN and Realms join counts towards the personal total");
		assertEquals(2, snap.serversJoined);
		assertEquals("hypixel.net", snap.topServer);
		assertEquals(5_000_000, snap.topServerMs);
		assertEquals(2, snap.servers.size());
		assertEquals(2, snap.servers.getFirst().joins);

		String json = Json.COMPACT.toJson(snap);
		for (String secret : List.of("192.168", "Secret", "friend-pc", "sp:", "chat", "disconnect")) {
			assertFalse(json.contains(secret), "upload must not contain " + secret + ": " + json);
		}
		JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
		assertEquals(Set.of("playtimeMs", "sessions", "totalJoins", "serversJoined", "topServer", "topServerMs", "servers"), obj.keySet());
	}

	@Test
	void snapshotWithoutGlobalSharingListsNoServers() {
		SessionRecord s = TestData.session(T0, 1_000_000);
		TestData.visit(s, "mc.hypixel.net", 25565, T0, 900_000);
		StatsSnapshot snap = StatsSnapshot.compute(List.of(s), false);
		assertTrue(snap.servers.isEmpty());
		assertEquals("hypixel.net", snap.topServer, "the top server is still shared with friends who may see stats");
	}

	@Test
	void snapshotCapsServerListAndFingerprintTracksChanges() {
		SessionRecord s = TestData.session(T0, 100_000_000);
		for (int i = 0; i < 30; i++) {
			TestData.visit(s, "network" + i + ".com", 25565, T0 + i * 1_000_000L, 60_000L * (i + 1));
		}
		StatsSnapshot a = StatsSnapshot.compute(List.of(s), true);
		assertEquals(StatsSnapshot.MAX_SERVERS, a.servers.size());
		assertEquals(30, a.serversJoined);
		assertEquals("network29.com", a.topServer);
		assertEquals(a.fingerprint(), StatsSnapshot.compute(List.of(s), true).fingerprint());
		s.visits.getFirst().durationMs += 30_000;
		assertEquals(a.fingerprint(), StatsSnapshot.compute(List.of(s), true).fingerprint(), "sub-minute changes do not trigger an upload");
		s.visits.getLast().durationMs += 120_000;
		assertNotEquals(a.fingerprint(), StatsSnapshot.compute(List.of(s), true).fingerprint());
	}

	@Test
	void onlyHttpsOrLocalServiceUrlsAreAccepted() {
		assertTrue(ApiClient.acceptable("https://sessioniq-api.example.workers.dev"));
		assertTrue(ApiClient.acceptable("http://127.0.0.1:8787"));
		assertTrue(ApiClient.acceptable("http://localhost:8787/"));
		assertFalse(ApiClient.acceptable("http://sessioniq-api.example.workers.dev"));
		assertFalse(ApiClient.acceptable("ftp://example.com"));
		assertFalse(ApiClient.acceptable("not a url"));
		assertFalse(ApiClient.acceptable(""));
	}
}
