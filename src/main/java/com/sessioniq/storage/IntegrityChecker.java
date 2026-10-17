package com.sessioniq.storage;

import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class IntegrityChecker {
	private IntegrityChecker() {
	}

	public static final class Report {
		public int sessionFiles;
		public int sessionErrors;
		public int chatPartitions;
		public long chatMessages;
		public int chatDamagedLines;
		public int sampleFiles;
		public int orphanChatDirs;
		public int orphanSampleFiles;
		public int quarantined;
		public final List<String> problems = new ArrayList<>();
		public final Map<String, Map<String, Integer>> counterMismatches = new HashMap<>();

		public boolean healthy() {
			return problems.isEmpty() && counterMismatches.isEmpty();
		}
	}

	public record Usage(long sessions, long chat, long samples, long backups, long other, long total) {
	}

	public static Report check(DataPaths paths, List<SessionRecord> snapshot) {
		Report r = new Report();
		Set<String> known = new HashSet<>();
		for (SessionRecord s : snapshot) {
			known.add(s.id);
		}
		List<String> loadProblems = new ArrayList<>();
		if (Files.isDirectory(paths.sessions)) {
			try (Stream<Path> walk = Files.walk(paths.sessions, 3)) {
				for (Path p : walk.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
					r.sessionFiles++;
					List<String> fileProblems = new ArrayList<>();
					String raw;
					try {
						raw = Files.readString(p);
						SessionRecord s = com.sessioniq.util.Json.read(raw, SessionRecord.class);
						if (!SessionRepository.repair(s, fileProblems)) {
							r.sessionErrors++;
							r.problems.add(p.getFileName() + ": missing id or start time");
						}
					} catch (Exception e) {
						r.sessionErrors++;
						r.problems.add(p.getFileName() + ": cannot be parsed (" + e.getClass().getSimpleName() + ")");
					}
					for (String f : fileProblems) {
						loadProblems.add(p.getFileName() + ": " + f);
					}
				}
			} catch (IOException e) {
				r.problems.add("Could not list sessions: " + StorageService.describe(e));
			}
		}
		r.problems.addAll(loadProblems);
		if (Files.isDirectory(paths.chat)) {
			try (Stream<Path> dirs = Files.list(paths.chat)) {
				for (Path dir : dirs.filter(Files::isDirectory).toList()) {
					String sid = dir.getFileName().toString();
					if (!known.contains(sid)) {
						r.orphanChatDirs++;
						r.problems.add("chat/" + sid + " has no matching session");
					}
					Map<String, Integer> perVisit = new HashMap<>();
					int total = 0;
					for (Path part : ChatArchive.partitions(dir)) {
						r.chatPartitions++;
						ChatArchive.PartitionData data = ChatArchive.readPartition(part);
						r.chatDamagedLines += data.damagedLines();
						if (data.damagedLines() > 0) {
							r.problems.add("chat/" + sid + "/" + part.getFileName() + ": " + data.damagedLines() + " damaged line(s) (skipped when reading)");
						}
						if (data.error() != null) {
							r.problems.add("chat/" + sid + "/" + part.getFileName() + ": " + data.error());
						}
						for (ChatRecord c : data.records()) {
							perVisit.merge(c.visitId == null ? "" : c.visitId, 1, Integer::sum);
							total++;
						}
					}
					r.chatMessages += total;
					SessionRecord s = snapshot.stream().filter(x -> x.id.equals(sid)).findFirst().orElse(null);
					if (s != null && !s.isActive() && s.chatCount != total) {
						r.counterMismatches.put(sid, perVisit);
					}
				}
			} catch (IOException e) {
				r.problems.add("Could not list chat: " + StorageService.describe(e));
			}
		}
		for (SessionRecord s : snapshot) {
			if (!s.isActive() && s.chatCount > 0 && !Files.isDirectory(paths.chatDir(s.id))) {
				r.counterMismatches.put(s.id, new HashMap<>());
			}
		}
		if (Files.isDirectory(paths.samples)) {
			try (Stream<Path> files = Files.list(paths.samples)) {
				for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
					r.sampleFiles++;
					String n = f.getFileName().toString();
					if (!known.contains(n.substring(0, n.length() - 6))) {
						r.orphanSampleFiles++;
						r.problems.add("samples/" + n + " has no matching session");
					}
				}
			} catch (IOException e) {
				r.problems.add("Could not list samples: " + StorageService.describe(e));
			}
		}
		if (Files.isDirectory(paths.quarantine)) {
			try (Stream<Path> q = Files.list(paths.quarantine)) {
				r.quarantined = (int) q.count();
			} catch (IOException ignored) {
			}
		}
		return r;
	}

	public static Usage usage(DataPaths paths) {
		long sessions = size(paths.sessions);
		long chat = size(paths.chat);
		long samples = size(paths.samples);
		long backups = size(paths.backups);
		long total = size(paths.root);
		return new Usage(sessions, chat, samples, backups, Math.max(0, total - sessions - chat - samples - backups), total);
	}

	private static long size(Path p) {
		if (!Files.exists(p)) {
			return 0;
		}
		try (Stream<Path> walk = Files.walk(p)) {
			return walk.filter(Files::isRegularFile).mapToLong(f -> {
				try {
					return Files.size(f);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			return 0;
		}
	}
}
