package com.sessioniq.storage;

import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MarkerRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Ids;
import com.sessioniq.util.Json;
import com.sessioniq.util.SiqLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class SessionRepository {
	private final DataPaths paths;
	private final StorageService storage;
	private final Map<String, SessionRecord> sessions = new HashMap<>();
	private final Map<String, Path> files = new HashMap<>();
	private List<SessionRecord> sortedCache;
	private int revision;
	private boolean loaded;
	private String activeId;

	public SessionRepository(DataPaths paths, StorageService storage) {
		this.paths = paths;
		this.storage = storage;
	}

	public record LoadResult(List<SessionRecord> records, Map<String, Path> files, List<String> problems) {
	}

	public static LoadResult loadAll(DataPaths paths) {
		List<SessionRecord> records = new ArrayList<>();
		Map<String, Path> files = new HashMap<>();
		List<String> problems = new ArrayList<>();
		Map<String, SessionRecord> byId = new HashMap<>();
		if (!Files.isDirectory(paths.sessions)) {
			return new LoadResult(records, files, problems);
		}
		List<Path> candidates;
		try (Stream<Path> walk = Files.walk(paths.sessions, 3)) {
			candidates = walk.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
		} catch (IOException e) {
			problems.add("Could not list session files: " + StorageService.describe(e));
			return new LoadResult(records, files, problems);
		}
		for (Path file : candidates) {
			SessionRecord record = readSession(file, paths, problems);
			if (record == null) {
				continue;
			}
			SessionRecord existing = byId.get(record.id);
			if (existing != null) {
				problems.add("Duplicate session id " + record.id + " in " + file.getFileName() + "; kept the most recent copy");
				if (existing.lastCheckpointAt >= record.lastCheckpointAt) {
					continue;
				}
			}
			byId.put(record.id, record);
			files.put(record.id, file);
		}
		records.addAll(byId.values());
		return new LoadResult(records, files, problems);
	}

	static SessionRecord readSession(Path file, DataPaths paths, List<String> problems) {
		String raw;
		try {
			raw = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			problems.add("Could not read " + file.getFileName() + ": " + StorageService.describe(e));
			return null;
		}
		SessionRecord record = null;
		String error = null;
		try {
			record = Json.read(raw, SessionRecord.class);
		} catch (Exception e) {
			error = e.getMessage();
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		if (Files.exists(temp)) {
			try {
				SessionRecord fromTemp = Json.read(Files.readString(temp, StandardCharsets.UTF_8), SessionRecord.class);
				if (repair(fromTemp, new ArrayList<>()) && (record == null || fromTemp.lastCheckpointAt > record.lastCheckpointAt)) {
					problems.add("Recovered " + file.getFileName() + " from an unfinished save");
					record = fromTemp;
					error = null;
				}
			} catch (Exception ignored) {
			}
		}
		if (record == null) {
			quarantine(file, paths, problems, "could not be parsed (" + error + ")");
			return null;
		}
		List<String> fixes = new ArrayList<>();
		if (!repair(record, fixes)) {
			quarantine(file, paths, problems, "is missing its id or start time");
			return null;
		}
		if (record.schema > SessionRecord.SCHEMA) {
			problems.add(file.getFileName() + " was written by a newer SessionIQ (schema " + record.schema + "); unknown fields are preserved");
		}
		for (String fix : fixes) {
			problems.add(file.getFileName() + ": " + fix);
		}
		return record;
	}

	private static void quarantine(Path file, DataPaths paths, List<String> problems, String why) {
		try {
			Path copy = AtomicFiles.quarantine(file, paths.quarantine);
			Path aside = file.resolveSibling(file.getFileName() + ".damaged");
			AtomicFiles.move(file, aside);
			problems.add(file.getFileName() + " " + why + "; preserved as " + copy.getFileName() + " in quarantine");
		} catch (IOException e) {
			problems.add(file.getFileName() + " " + why + " and could not be moved aside: " + StorageService.describe(e));
		}
	}

	public static boolean repair(SessionRecord r, List<String> fixes) {
		if (r.id == null || r.id.isBlank() || !Ids.isSafeFileComponent(r.id) || r.startedAt <= 0) {
			return false;
		}
		if (r.visits == null) {
			r.visits = new ArrayList<>();
			fixes.add("missing visit list");
		}
		if (r.disconnects == null) {
			r.disconnects = new ArrayList<>();
			fixes.add("missing disconnect list");
		}
		if (r.markers == null) {
			r.markers = new ArrayList<>();
		}
		r.visits.removeIf(v -> v == null || v.id == null || v.serverKey == null);
		r.disconnects.removeIf(d -> d == null || d.id == null);
		r.markers.removeIf(m -> m == null || m.id == null);
		Set<String> visitIds = new HashSet<>();
		for (VisitRecord v : r.visits) {
			if (!visitIds.add(v.id)) {
				v.id = Ids.visit();
				fixes.add("duplicate visit id replaced");
			}
			if (v.durationMs < 0) {
				v.durationMs = 0;
				fixes.add("negative visit duration reset");
			}
			v.pausedMs = Math.max(0, Math.min(v.pausedMs, v.durationMs));
			if (v.kind == null) {
				v.kind = VisitRecord.KIND_MULTIPLAYER;
			}
		}
		Set<String> dIds = new HashSet<>();
		for (DisconnectRecord d : r.disconnects) {
			if (!dIds.add(d.id)) {
				d.id = Ids.disconnect();
			}
			if (d.evidence == null) {
				d.evidence = new ArrayList<>();
			}
			if (d.sessionId == null) {
				d.sessionId = r.id;
			}
		}
		Set<String> mIds = new HashSet<>();
		for (MarkerRecord m : r.markers) {
			if (!mIds.add(m.id)) {
				m.id = Ids.marker();
			}
		}
		if (r.runtimeMs < 0) {
			r.runtimeMs = 0;
		}
		if (r.status == null) {
			r.status = SessionRecord.STATUS_ACTIVE;
		}
		r.recomputeTotals();
		return true;
	}

	public void publish(LoadResult result) {
		for (SessionRecord r : result.records()) {
			if (activeId != null && activeId.equals(r.id)) {
				continue;
			}
			sessions.put(r.id, r);
		}
		files.putAll(result.files());
		loaded = true;
		changed();
	}

	public void reset(LoadResult result) {
		SessionRecord active = activeId == null ? null : sessions.get(activeId);
		Path activeFile = activeId == null ? null : files.get(activeId);
		sessions.clear();
		files.clear();
		if (active != null) {
			sessions.put(active.id, active);
			if (activeFile != null) {
				files.put(active.id, activeFile);
			}
		}
		publish(result);
	}

	public boolean isLoaded() {
		return loaded;
	}

	public int revision() {
		return revision;
	}

	public void setActive(SessionRecord active) {
		activeId = active == null ? null : active.id;
		if (active != null) {
			sessions.put(active.id, active);
		}
		changed();
	}

	public String activeId() {
		return activeId;
	}

	public SessionRecord get(String id) {
		return id == null ? null : sessions.get(id);
	}

	public boolean contains(String id) {
		return sessions.containsKey(id);
	}

	public List<SessionRecord> sorted() {
		if (sortedCache == null) {
			List<SessionRecord> list = new ArrayList<>(sessions.values());
			list.sort(Comparator.comparingLong((SessionRecord s) -> s.startedAt).reversed());
			sortedCache = Collections.unmodifiableList(list);
		}
		return sortedCache;
	}

	public List<SessionRecord> snapshot() {
		List<SessionRecord> out = new ArrayList<>(sessions.size());
		for (SessionRecord r : sorted()) {
			out.add(r.id.equals(activeId) ? Json.copy(r, SessionRecord.class) : r);
		}
		return out;
	}

	public void save(SessionRecord record) {
		Path file = files.computeIfAbsent(record.id, id -> paths.sessionFile(id, record.startedAt));
		storage.writeAtomic(file, Json.write(record, true));
	}

	public void replace(SessionRecord updated) {
		if (updated.id.equals(activeId)) {
			throw new IllegalArgumentException("Use the live record for the active session");
		}
		sessions.put(updated.id, updated);
		save(updated);
		changed();
	}

	public SessionRecord editable(String id) {
		SessionRecord r = sessions.get(id);
		if (r == null) {
			return null;
		}
		return id.equals(activeId) ? r : Json.copy(r, SessionRecord.class);
	}

	public void commit(SessionRecord edited) {
		if (edited.id.equals(activeId)) {
			save(edited);
			changed();
		} else {
			replace(edited);
		}
	}

	public void delete(String id) {
		if (id.equals(activeId)) {
			throw new IllegalArgumentException("The active session cannot be deleted while it is recording");
		}
		SessionRecord removed = sessions.remove(id);
		Path file = files.remove(id);
		Path sessionFile = file != null ? file : removed != null ? paths.sessionFile(id, removed.startedAt) : null;
		Path chatDir = paths.chatDir(id);
		Path samples = paths.samplesFile(id);
		storage.runIo(() -> {
			try {
				if (sessionFile != null) {
					Files.deleteIfExists(sessionFile);
					Files.deleteIfExists(sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp"));
				}
				deleteTree(chatDir);
				Files.deleteIfExists(samples);
			} catch (IOException e) {
				storage.report("Could not delete all files of session " + id, StorageService.describe(e));
			}
		});
		changed();
	}

	static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
			for (Path p : all) {
				Files.deleteIfExists(p);
			}
		}
	}

	public void changed() {
		sortedCache = null;
		revision++;
	}

	public int size() {
		return sessions.size();
	}

	public void logSummary() {
		SiqLog.info("SessionIQ loaded {} session(s)", sessions.size());
	}
}
