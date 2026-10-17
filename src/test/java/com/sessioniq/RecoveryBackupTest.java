package com.sessioniq;

import com.sessioniq.backup.BackupService;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.ChatArchive;
import com.sessioniq.storage.DataPaths;
import com.sessioniq.storage.RecoveryManager;
import com.sessioniq.storage.SessionRepository;
import com.sessioniq.storage.StorageService;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Ids;
import com.sessioniq.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryBackupTest {
	@TempDir
	Path dir;
	DataPaths paths;
	StorageService storage;

	@BeforeEach
	void setUp() {
		paths = new DataPaths(dir.resolve("config/sessioniq"));
		storage = new StorageService();
	}

	@AfterEach
	void tearDown() {
		storage.shutdown(2000);
	}

	private SessionRecord interruptedSession(long now) throws Exception {
		SessionRecord s = TestData.session(now - 3_600_000, 1_800_000);
		s.status = SessionRecord.STATUS_ACTIVE;
		s.endedAt = null;
		s.lastCheckpointAt = now - 1_800_000;
		VisitRecord v = TestData.visit(s, "a.example.com", 25565, s.startedAt + 60_000, 1_000_000);
		v.open = true;
		v.leftAt = null;
		AtomicFiles.writeString(paths.sessionFile(s.id, s.startedAt), Json.write(s, true));
		ChatArchive chat = new ChatArchive(paths, storage);
		for (int i = 0; i < 3; i++) {
			ChatRecord r = new ChatRecord();
			r.id = Ids.chat();
			r.at = s.lastCheckpointAt + 10_000 * (i + 1);
			r.sessionId = s.id;
			r.visitId = v.id;
			r.serverKey = v.serverKey;
			r.text = "late " + i;
			r.category = ChatCategory.CHAT.id;
			r.origin = ChatRecord.ORIGIN_PLAYER;
			chat.append(r);
		}
		chat.flush();
		storage.flush(5000);
		return s;
	}

	@Test
	void interruptedSessionIsFinalizedOnceWithUnknownCause() throws Exception {
		long now = System.currentTimeMillis();
		SessionRecord original = interruptedSession(now);
		SessionRepository.LoadResult load = SessionRepository.loadAll(paths);
		RecoveryManager.Outcome out = RecoveryManager.recover(paths, load.records(), load.files(), null, dir, now, 1);
		assertEquals(List.of(original.id), out.recoveredIds());
		SessionRecord s = SessionRepository.loadAll(paths).records().getFirst();
		assertEquals(SessionRecord.STATUS_INTERRUPTED, s.status);
		assertTrue(s.recovered);
		VisitRecord v = s.visits.getFirst();
		assertFalse(v.open);
		assertTrue(v.interrupted);
		assertEquals(1_030_000, v.durationMs, "extended to the last chat timestamp, no more");
		assertEquals(3, s.chatCount, "chat written after the checkpoint is counted");
		DisconnectRecord d = s.disconnects.getFirst();
		assertEquals(DisconnectCategory.ABNORMAL_TERMINATION.name(), d.category);
		assertEquals(DisconnectRecord.Certainty.UNKNOWN.name(), d.certainty, "no crash report: cause stays unknown");

		SessionRepository.LoadResult again = SessionRepository.loadAll(paths);
		RecoveryManager.Outcome second = RecoveryManager.recover(paths, again.records(), again.files(), null, dir, now + 60_000, 1);
		assertTrue(second.recoveredIds().isEmpty());
		SessionRecord after = SessionRepository.loadAll(paths).records().getFirst();
		assertEquals(1_030_000, after.visits.getFirst().durationMs);
		assertEquals(1, after.disconnects.size());
	}

	@Test
	void crashReportIsCitedAsEvidence() throws Exception {
		long now = System.currentTimeMillis();
		SessionRecord s = interruptedSession(now);
		Path report = dir.resolve("crash-reports/crash-2026-10-15_12.00.00-client.txt");
		Files.createDirectories(report.getParent());
		Files.writeString(report, "---- Minecraft Crash Report ----\n// Oops\n\nTime: x\nDescription: Rendering overlay\n");
		Files.setLastModifiedTime(report, FileTime.fromMillis(s.lastCheckpointAt + 5000));
		SessionRepository.LoadResult load = SessionRepository.loadAll(paths);
		RecoveryManager.recover(paths, load.records(), load.files(), null, dir, now, 1);
		DisconnectRecord d = SessionRepository.loadAll(paths).records().getFirst().disconnects.getFirst();
		assertEquals(DisconnectRecord.Certainty.INFERRED.name(), d.certainty);
		assertTrue(d.evidence.stream().anyMatch(e -> e.contains("Rendering overlay")));
	}

	@Test
	void backupVerifyDetectsTamperingAndRestoreProtectsActiveSession() throws Exception {
		BackupService backups = new BackupService(paths, storage, "test");
		SessionRecord kept = TestData.session(1_760_000_000_000L, 1000);
		SessionRecord deletedLater = TestData.session(1_760_100_000_000L, 2000);
		for (SessionRecord s : List.of(kept, deletedLater)) {
			AtomicFiles.writeString(paths.sessionFile(s.id, s.startedAt), Json.write(s, true));
		}
		Path archive = backups.createNow("manual", false);
		BackupService.VerifyResult ok = backups.verify(archive);
		assertTrue(ok.ok(), String.valueOf(ok.problems()));
		assertEquals(2, ok.sessionCount());

		Path tampered = dir.resolve("tampered.zip");
		try (ZipFile in = new ZipFile(archive.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tampered))) {
			for (var e : java.util.Collections.list(in.entries())) {
				out.putNextEntry(new ZipEntry(e.getName()));
				byte[] data = in.getInputStream(e).readAllBytes();
				if (e.getName().startsWith("sessions/")) {
					data = new String(data).replace("\"runtimeMs\": 1000", "\"runtimeMs\": 9999").getBytes();
				}
				out.write(data);
				out.closeEntry();
			}
		}
		assertFalse(backups.verify(tampered).ok());
		assertThrows(java.io.IOException.class, () -> backups.restore(tampered, BackupService.RestoreMode.REPLACE, null, Set.of()));

		Files.delete(paths.sessionFile(deletedLater.id, deletedLater.startedAt));
		SessionRecord active = TestData.session(1_760_200_000_000L, 500);
		active.status = SessionRecord.STATUS_ACTIVE;
		AtomicFiles.writeString(paths.sessionFile(active.id, active.startedAt), Json.write(active, true));
		Set<String> existing = new HashSet<>(List.of(kept.id, active.id));
		BackupService.RestoreResult merged = backups.restore(archive, BackupService.RestoreMode.MERGE, active.id, existing);
		assertEquals(1, merged.sessionsRestored());
		assertNotNull(merged.safetyBackup());
		assertEquals(3, SessionRepository.loadAll(paths).records().size());

		BackupService.RestoreResult replaced = backups.restore(archive, BackupService.RestoreMode.REPLACE, active.id, existing);
		assertEquals(2, replaced.sessionsRestored());
		List<SessionRecord> after = SessionRepository.loadAll(paths).records();
		assertTrue(after.stream().anyMatch(s -> s.id.equals(active.id)));
		assertEquals(3, after.size());
	}

	@Test
	void rotationOnlyRemovesAutomaticBackups() throws Exception {
		BackupService backups = new BackupService(paths, storage, "test");
		SessionRecord s = TestData.session(1_760_000_000_000L, 1000);
		AtomicFiles.writeString(paths.sessionFile(s.id, s.startedAt), Json.write(s, true));
		Path manual = backups.createNow("manual", false);
		for (int i = 0; i < 4; i++) {
			backups.createNow("automatic", true);
			Thread.sleep(5);
		}
		List<String> removed = backups.rotate(2);
		assertEquals(2, removed.size());
		assertTrue(Files.exists(manual));
		assertTrue(Files.exists(paths.sessionFile(s.id, s.startedAt)), "primary records are never touched");
		assertEquals(3, backups.list().size());
	}
}
