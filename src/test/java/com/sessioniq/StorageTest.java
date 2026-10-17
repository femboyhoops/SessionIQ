package com.sessioniq;

import com.google.gson.JsonParser;
import com.sessioniq.config.ConfigManager;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.storage.DataPaths;
import com.sessioniq.storage.Migrations;
import com.sessioniq.storage.SessionRepository;
import com.sessioniq.storage.StorageService;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {
	@TempDir
	Path dir;

	@Test
	void atomicWriteReplacesAndLeavesNoTemp() throws Exception {
		Path f = dir.resolve("a/b.json");
		AtomicFiles.writeString(f, "{\"x\":1}");
		AtomicFiles.writeString(f, "{\"x\":2}");
		assertEquals("{\"x\":2}", Files.readString(f));
		assertFalse(Files.exists(dir.resolve("a/b.json.tmp")));
	}

	@Test
	void appendAfterTruncatedLineStartsFreshLine() throws Exception {
		Path f = dir.resolve("log.jsonl");
		Files.writeString(f, "{\"ok\":1}\n{\"trunc", StandardCharsets.UTF_8);
		AtomicFiles.appendLines(f, List.of("{\"ok\":2}"));
		List<String> lines = Files.readAllLines(f);
		assertEquals(3, lines.size());
		assertEquals("{\"ok\":2}", lines.get(2));
	}

	@Test
	void unknownFieldsSurviveRoundTrip() {
		String json = "{\"id\":\"20260101-000000-abcd\",\"startedAt\":5,\"futureField\":{\"a\":[1,2]}}";
		SessionRecord r = Json.read(json, SessionRecord.class);
		String out = Json.write(r, false);
		assertTrue(JsonParser.parseString(out).getAsJsonObject().has("futureField"));
	}

	@Test
	void configInvalidFieldsResetIndividually() throws Exception {
		DataPaths p = new DataPaths(dir);
		Files.createDirectories(p.root);
		Files.writeString(p.config, "{\"pingIntervalSec\":\"abc\",\"clock24h\":false,\"memoryIntervalSec\":9999,\"theme\":\"Violet\",\"extra\":42}");
		ConfigManager cm = new ConfigManager(p.config, p.quarantine);
		cm.load();
		SiqConfig c = cm.get();
		assertEquals(10, c.pingIntervalSec, "invalid type falls back to default");
		assertEquals(30, c.memoryIntervalSec, "out of range falls back to default");
		assertFalse(c.clock24h, "valid values are kept");
		assertEquals("Violet", c.theme);
		assertTrue(cm.issues().size() >= 2);
		assertTrue(Files.readString(p.config).contains("\"extra\""), "unknown fields are preserved");
	}

	@Test
	void malformedConfigIsQuarantinedNotLost() throws Exception {
		DataPaths p = new DataPaths(dir);
		Files.createDirectories(p.root);
		Files.writeString(p.config, "{ this is not json");
		ConfigManager cm = new ConfigManager(p.config, p.quarantine);
		cm.load();
		assertEquals(new SiqConfig().pingIntervalSec, cm.get().pingIntervalSec);
		try (var s = Files.list(p.quarantine)) {
			Path copy = s.findFirst().orElseThrow();
			assertEquals("{ this is not json", Files.readString(copy));
		}
	}

	@Test
	void loadSkipsDamagedFilesAndKeepsTheRest() throws Exception {
		DataPaths p = new DataPaths(dir);
		SessionRecord good = TestData.session(1_760_000_000_000L, 60_000);
		TestData.visit(good, "play.example.com", 25565, good.startedAt + 1000, 30_000);
		Path goodFile = p.sessionFile(good.id, good.startedAt);
		AtomicFiles.writeString(goodFile, Json.write(good, true));
		Path bad = p.sessions.resolve("2025-10/20251009-000000-dead.json");
		AtomicFiles.writeString(bad, "{\"id\":\"20251009-000000-dead\",\"startedAt\":17600");
		SessionRepository.LoadResult r = SessionRepository.loadAll(p);
		assertEquals(1, r.records().size());
		assertEquals(good.id, r.records().getFirst().id);
		assertEquals(1, r.records().getFirst().visits.size());
		assertFalse(r.problems().isEmpty());
		assertTrue(Files.exists(bad.resolveSibling(bad.getFileName() + ".damaged")), "damaged file is kept, renamed");
		assertTrue(Files.list(p.quarantine).findAny().isPresent(), "a copy is preserved in quarantine");
	}

	@Test
	void interruptedSavePrefersCompleteNewerTemp() throws Exception {
		DataPaths p = new DataPaths(dir);
		SessionRecord s = TestData.session(1_760_000_000_000L, 60_000);
		Path file = p.sessionFile(s.id, s.startedAt);
		AtomicFiles.writeString(file, Json.write(s, true));
		s.lastCheckpointAt += 30_000;
		s.runtimeMs += 30_000;
		Files.writeString(file.resolveSibling(file.getFileName() + ".tmp"), Json.write(s, true));
		SessionRepository.LoadResult r = SessionRepository.loadAll(p);
		assertEquals(90_000, r.records().getFirst().runtimeMs);
	}

	@Test
	void repositorySaveIsAsyncAndDurable() throws Exception {
		DataPaths p = new DataPaths(dir);
		StorageService storage = new StorageService();
		SessionRepository repo = new SessionRepository(p, storage);
		repo.publish(new SessionRepository.LoadResult(new ArrayList<>(), new java.util.HashMap<>(), new ArrayList<>()));
		SessionRecord s = TestData.session(1_760_000_000_000L, 1000);
		repo.setActive(s);
		for (int i = 0; i < 50; i++) {
			s.runtimeMs = i;
			repo.save(s);
		}
		assertTrue(storage.flush(5000));
		SessionRecord back = Json.read(Files.readString(p.sessionFile(s.id, s.startedAt)), SessionRecord.class);
		assertEquals(49, back.runtimeMs, "coalesced writes keep the latest content");
		storage.shutdown(1000);
	}

	@Test
	void migrationsCreateManifestAndDetectNewerSchema() throws Exception {
		DataPaths p = new DataPaths(dir);
		Files.createDirectories(p.root);
		Migrations.Status st = Migrations.ensure(p, "test", label -> "none");
		assertEquals(Migrations.CURRENT, st.schema());
		assertNotNull(JsonParser.parseString(Files.readString(p.manifest)).getAsJsonObject().get("createdAt"));
		Files.writeString(p.manifest, "{\"schema\":99,\"history\":[]}");
		Migrations.Status newer = Migrations.ensure(p, "test", label -> "none");
		assertTrue(newer.newerThanSupported());
		assertEquals(99, JsonParser.parseString(Files.readString(p.manifest)).getAsJsonObject().get("schema").getAsInt(), "never downgraded");
	}
}
