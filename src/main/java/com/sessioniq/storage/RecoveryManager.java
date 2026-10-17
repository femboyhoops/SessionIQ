package com.sessioniq.storage;

import com.sessioniq.model.ActiveState;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MetricSummary;
import com.sessioniq.model.SampleRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.util.Ids;
import com.sessioniq.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public final class RecoveryManager {
	private static final long MAX_EXTENSION_MS = 5 * 60 * 1000L;
	private static final long LIVE_HEARTBEAT_MS = 2 * 60 * 1000L;

	private RecoveryManager() {
	}

	public record Outcome(List<String> recoveredIds, List<String> notes) {
	}

	public static Outcome recover(DataPaths paths, List<SessionRecord> loaded, Map<String, Path> files, ActiveState previousState,
								  Path gameDir, long now, long currentPid) {
		List<String> recovered = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		for (SessionRecord s : loaded) {
			if (!s.isActive()) {
				continue;
			}
			if (previousState != null && s.id.equals(previousState.sessionId) && previousState.pid != currentPid
				&& now - previousState.heartbeatAt < LIVE_HEARTBEAT_MS && ProcessHandle.of(previousState.pid).map(ProcessHandle::isAlive).orElse(false)) {
				notes.add("Session " + s.id + " belongs to another running Minecraft instance and was left untouched");
				continue;
			}
			recoverOne(paths, s, gameDir, now);
			Path file = files.getOrDefault(s.id, paths.sessionFile(s.id, s.startedAt));
			try {
				com.sessioniq.util.AtomicFiles.writeString(file, Json.write(s, true));
				recovered.add(s.id);
			} catch (IOException e) {
				notes.add("Recovered session " + s.id + " could not be saved: " + StorageService.describe(e));
			}
		}
		return new Outcome(recovered, notes);
	}

	static void recoverOne(DataPaths paths, SessionRecord s, Path gameDir, long now) {
		List<ChatRecord> chat = new ArrayList<>();
		for (Path p : ChatArchive.partitions(paths.chatDir(s.id))) {
			chat.addAll(ChatArchive.readPartition(p).records());
		}
		List<SampleRecord> samples = readSamples(paths.samplesFile(s.id));
		long lastEvidence = s.lastCheckpointAt;
		for (ChatRecord c : chat) {
			lastEvidence = Math.max(lastEvidence, c.at);
		}
		for (SampleRecord x : samples) {
			lastEvidence = Math.max(lastEvidence, x.at);
		}
		lastEvidence = Math.min(lastEvidence, Math.min(now, s.lastCheckpointAt + MAX_EXTENSION_MS));
		long extension = Math.max(0, lastEvidence - s.lastCheckpointAt);

		List<String> crashEvidence = findCrashEvidence(gameDir, s.lastCheckpointAt - 5_000, now);
		s.runtimeMs += extension;

		for (VisitRecord v : s.visits) {
			if (!v.open) {
				continue;
			}
			v.open = false;
			v.interrupted = true;
			v.durationMs += extension;
			v.leftAt = lastEvidence;
			v.endReason = VisitRecord.END_INTERRUPTED;
			DisconnectRecord d = new DisconnectRecord();
			d.id = Ids.disconnect();
			d.at = lastEvidence;
			d.sessionId = s.id;
			d.visitId = v.id;
			d.serverKey = v.serverKey;
			d.serverName = v.name;
			d.address = v.address;
			d.phase = "play";
			d.category = DisconnectCategory.ABNORMAL_TERMINATION.name();
			d.certainty = crashEvidence.isEmpty() ? DisconnectRecord.Certainty.UNKNOWN.name() : DisconnectRecord.Certainty.INFERRED.name();
			d.recovered = true;
			d.evidence.add(v.isMultiplayer() ? "Minecraft stopped while connected to the server, without a normal shutdown"
				: "Minecraft stopped while in this single-player world, without a normal shutdown");
			d.evidence.add("Last recorded activity: " + stamp(lastEvidence));
			if (crashEvidence.isEmpty()) {
				d.evidence.add("No crash report was found; the cause may be a crash, a forced close, or a power loss");
			} else {
				d.evidence.addAll(crashEvidence);
			}
			v.disconnectId = d.id;
			s.disconnects.add(d);
		}

		Map<String, Integer> perVisit = new HashMap<>();
		for (ChatRecord c : chat) {
			perVisit.merge(c.visitId == null ? "" : c.visitId, 1, Integer::sum);
		}
		s.chatCount = chat.size();
		for (VisitRecord v : s.visits) {
			v.chatCount = perVisit.getOrDefault(v.id, 0);
		}
		if (!samples.isEmpty()) {
			Map<String, MetricSummary> ping = new HashMap<>();
			MetricSummary memory = null;
			for (SampleRecord x : samples) {
				if (SampleRecord.KIND_PING.equals(x.kind) && x.visitId != null) {
					ping.computeIfAbsent(x.visitId, k -> new MetricSummary()).add(x.value);
				} else if (SampleRecord.KIND_MEMORY.equals(x.kind)) {
					if (memory == null) {
						memory = new MetricSummary();
					}
					memory.add(x.value);
					if (x.max != null) {
						s.memoryMaxMb = Math.round(x.max);
					}
				}
			}
			for (VisitRecord v : s.visits) {
				if (ping.containsKey(v.id)) {
					v.ping = ping.get(v.id);
				}
			}
			if (memory != null) {
				s.memoryUsedMb = memory;
			}
		}

		s.status = SessionRecord.STATUS_INTERRUPTED;
		s.endedAt = lastEvidence;
		s.recovered = true;
		s.recoveredAt = now;
		StringBuilder note = new StringBuilder("Minecraft did not shut down normally. Recorded data was kept up to the last activity at ")
			.append(stamp(lastEvidence)).append('.');
		if (extension > 0) {
			note.append(" Durations were extended by ").append(extension / 1000).append(" s past the last checkpoint, based on chat or sample timestamps.");
		}
		note.append(crashEvidence.isEmpty() ? " The cause is unknown." : " A crash report from that time was found (see disconnect evidence).");
		s.recoveryNote = note.toString();
		s.recomputeTotals();
	}

	static List<String> findCrashEvidence(Path gameDir, long from, long to) {
		List<String> out = new ArrayList<>();
		if (gameDir == null) {
			return out;
		}
		scan(gameDir.resolve("crash-reports"), p -> p.getFileName().toString().endsWith(".txt"), from, to, out, true);
		scan(gameDir, p -> p.getFileName().toString().startsWith("hs_err_pid"), from, to, out, false);
		return out;
	}

	private static void scan(Path dir, Function<Path, Boolean> filter, long from, long to, List<String> out, boolean readDescription) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.filter(Files::isRegularFile).filter(filter::apply).toList()) {
				long modified = Files.getLastModifiedTime(p).toMillis();
				if (modified < from || modified > to) {
					continue;
				}
				String line = "Crash report written at " + stamp(modified) + ": " + p.getFileName();
				if (readDescription) {
					String description = readDescription(p);
					if (description != null) {
						line += " (\"" + description + "\")";
					}
				} else {
					line = "JVM fatal error log written at " + stamp(modified) + ": " + p.getFileName();
				}
				out.add(line);
			}
		} catch (IOException ignored) {
		}
	}

	private static String readDescription(Path report) {
		try (BufferedReader r = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
			String line;
			int n = 0;
			while ((line = r.readLine()) != null && n++ < 40) {
				if (line.startsWith("Description:")) {
					return com.sessioniq.track.Sanitize.oneLine(line.substring("Description:".length()).trim(), 160);
				}
			}
		} catch (IOException ignored) {
		}
		return null;
	}

	static List<SampleRecord> readSamples(Path file) {
		List<SampleRecord> out = new ArrayList<>();
		if (!Files.exists(file)) {
			return out;
		}
		try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null) {
				try {
					SampleRecord s = Json.COMPACT.fromJson(line, SampleRecord.class);
					if (s != null && s.kind != null && s.at > 0) {
						out.add(s);
					}
				} catch (Exception ignored) {
				}
			}
		} catch (IOException ignored) {
		}
		return out;
	}

	private static String stamp(long millis) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
	}
}
