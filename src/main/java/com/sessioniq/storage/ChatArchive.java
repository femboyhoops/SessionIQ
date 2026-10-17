package com.sessioniq.storage;

import com.google.gson.JsonParseException;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class ChatArchive {
	public static final int PARTITION_LINES = 5000;
	private static final int FLUSH_BATCH = 50;

	private final DataPaths paths;
	private final StorageService storage;
	private final Map<String, List<String>> pending = new LinkedHashMap<>();
	private int pendingCount;
	private final Map<String, int[]> partitionState = new HashMap<>();

	public ChatArchive(DataPaths paths, StorageService storage) {
		this.paths = paths;
		this.storage = storage;
	}

	public void append(ChatRecord record) {
		pending.computeIfAbsent(record.sessionId, k -> new ArrayList<>()).add(Json.COMPACT.toJson(record));
		pendingCount++;
		if (pendingCount >= FLUSH_BATCH) {
			flush();
		}
	}

	public int pendingCount() {
		return pendingCount;
	}

	public void flush() {
		if (pendingCount == 0) {
			return;
		}
		Map<String, List<String>> batch = new LinkedHashMap<>(pending);
		pending.clear();
		pendingCount = 0;
		storage.runIo(() -> {
			for (Map.Entry<String, List<String>> e : batch.entrySet()) {
				writeLines(e.getKey(), e.getValue());
			}
		});
	}

	public void discardPending(Predicate<String> sessionFilter) {
		pending.entrySet().removeIf(e -> {
			if (sessionFilter.test(e.getKey())) {
				pendingCount -= e.getValue().size();
				return true;
			}
			return false;
		});
	}

	private void writeLines(String sessionId, List<String> lines) {
		Path dir = paths.chatDir(sessionId);
		int[] state = partitionState.computeIfAbsent(sessionId, id -> scanState(dir));
		int offset = 0;
		while (offset < lines.size()) {
			int room = PARTITION_LINES - state[1];
			if (room <= 0) {
				state[0]++;
				state[1] = 0;
				room = PARTITION_LINES;
			}
			int end = Math.min(lines.size(), offset + room);
			Path part = partitionFile(dir, state[0]);
			try {
				AtomicFiles.appendLines(part, lines.subList(offset, end));
				state[1] += end - offset;
			} catch (IOException e) {
				storage.report("Could not append chat to " + dir.getFileName() + "/" + part.getFileName(), StorageService.describe(e));
				return;
			}
			offset = end;
		}
	}

	private int[] scanState(Path dir) {
		List<Path> parts = partitions(dir);
		if (parts.isEmpty()) {
			return new int[]{1, 0};
		}
		Path last = parts.getLast();
		int index = partitionIndex(last);
		int lines = 0;
		try (Stream<String> s = Files.lines(last, StandardCharsets.UTF_8)) {
			lines = (int) s.count();
		} catch (Exception e) {
			return new int[]{index + 1, 0};
		}
		return new int[]{index, lines};
	}

	static Path partitionFile(Path dir, int index) {
		return dir.resolve(String.format(java.util.Locale.ROOT, "part-%05d.jsonl", index));
	}

	static int partitionIndex(Path file) {
		String n = file.getFileName().toString();
		try {
			return Integer.parseInt(n.substring(5, n.length() - 6));
		} catch (Exception e) {
			return 0;
		}
	}

	public List<Path> partitions(String sessionId) {
		return partitions(paths.chatDir(sessionId));
	}

	static List<Path> partitions(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith("part-") && n.endsWith(".jsonl");
			}).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public static PartitionData readPartition(Path file) {
		List<ChatRecord> records = new ArrayList<>();
		int damaged = 0;
		String error = null;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				try {
					ChatRecord r = Json.COMPACT.fromJson(line, ChatRecord.class);
					if (r == null || r.id == null || r.text == null || r.at <= 0) {
						damaged++;
					} else {
						records.add(r);
					}
				} catch (JsonParseException | IllegalStateException e) {
					damaged++;
				}
			}
		} catch (IOException e) {
			error = StorageService.describe(e);
		}
		return new PartitionData(records, damaged, error);
	}

	public record PartitionData(List<ChatRecord> records, int damagedLines, String error) {
	}

	public List<ChatRecord> readSession(String sessionId) {
		List<ChatRecord> out = new ArrayList<>();
		for (Path p : partitions(sessionId)) {
			out.addAll(readPartition(p).records());
		}
		return out;
	}

	public Map<String, Integer> countByVisit(String sessionId) {
		Map<String, Integer> counts = new HashMap<>();
		for (Path p : partitions(sessionId)) {
			for (ChatRecord r : readPartition(p).records()) {
				counts.merge(r.visitId == null ? "" : r.visitId, 1, Integer::sum);
			}
		}
		return counts;
	}

	public CompletableFuture<List<ChatRecord>> deleteMessages(Map<String, Set<String>> idsBySession) {
		flush();
		return storage.submitIo(() -> {
			List<ChatRecord> removed = new ArrayList<>();
			for (Map.Entry<String, Set<String>> e : idsBySession.entrySet()) {
				removed.addAll(rewrite(e.getKey(), r -> e.getValue().contains(r.id)));
			}
			return removed;
		});
	}

	public CompletableFuture<List<ChatRecord>> deleteWhere(Collection<String> sessionIds, Predicate<ChatRecord> match) {
		flush();
		return storage.submitIo(() -> {
			List<ChatRecord> removed = new ArrayList<>();
			for (String sessionId : sessionIds) {
				removed.addAll(rewrite(sessionId, match));
			}
			return removed;
		});
	}

	private List<ChatRecord> rewrite(String sessionId, Predicate<ChatRecord> remove) throws IOException {
		List<ChatRecord> removed = new ArrayList<>();
		for (Path part : partitions(sessionId)) {
			List<String> keep = new ArrayList<>();
			boolean changed = false;
			List<String> lines = Files.readAllLines(part, StandardCharsets.UTF_8);
			for (String line : lines) {
				if (line.isBlank()) {
					continue;
				}
				ChatRecord r;
				try {
					r = Json.COMPACT.fromJson(line, ChatRecord.class);
				} catch (Exception ex) {
					keep.add(line);
					continue;
				}
				if (r != null && r.id != null && remove.test(r)) {
					removed.add(r);
					changed = true;
				} else {
					keep.add(line);
				}
			}
			if (changed) {
				if (keep.isEmpty()) {
					Files.deleteIfExists(part);
				} else {
					AtomicFiles.writeString(part, String.join("\n", keep) + "\n");
				}
			}
		}
		partitionState.remove(sessionId);
		return removed;
	}

	public void deleteSession(String sessionId) {
		discardPending(sessionId::equals);
		storage.runIo(() -> {
			try {
				SessionRepository.deleteTree(paths.chatDir(sessionId));
			} catch (IOException e) {
				storage.report("Could not delete chat of session " + sessionId, StorageService.describe(e));
			}
			partitionState.remove(sessionId);
		});
	}

	public CompletableFuture<Void> deleteAll() {
		pending.clear();
		pendingCount = 0;
		return storage.submitIo(() -> {
			SessionRepository.deleteTree(paths.chat);
			partitionState.clear();
			return null;
		});
	}
}
