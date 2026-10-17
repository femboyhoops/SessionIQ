package com.sessioniq.storage;

import com.sessioniq.model.SampleRecord;
import com.sessioniq.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SampleStore {
	private final DataPaths paths;
	private final StorageService storage;
	private final List<String> pending = new ArrayList<>();
	private String pendingSession;

	public SampleStore(DataPaths paths, StorageService storage) {
		this.paths = paths;
		this.storage = storage;
	}

	public void append(String sessionId, SampleRecord sample) {
		if (pendingSession != null && !pendingSession.equals(sessionId)) {
			flush();
		}
		pendingSession = sessionId;
		pending.add(Json.COMPACT.toJson(sample));
		if (pending.size() >= 40) {
			flush();
		}
	}

	public void flush() {
		if (pending.isEmpty() || pendingSession == null) {
			return;
		}
		storage.appendLines(paths.samplesFile(pendingSession), new ArrayList<>(pending));
		pending.clear();
	}

	public List<SampleRecord> read(String sessionId) {
		List<SampleRecord> out = new ArrayList<>();
		Path file = paths.samplesFile(sessionId);
		if (!Files.exists(file)) {
			return out;
		}
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				try {
					SampleRecord s = Json.COMPACT.fromJson(line, SampleRecord.class);
					if (s != null && s.kind != null && s.at > 0 && Double.isFinite(s.value)) {
						out.add(s);
					}
				} catch (Exception ignored) {
				}
			}
		} catch (IOException e) {
			storage.report("Could not read samples for " + sessionId, StorageService.describe(e));
		}
		return out;
	}

	public void deleteVisits(String sessionId, java.util.Set<String> visitIds) {
		flush();
		storage.runIo(() -> {
			Path file = paths.samplesFile(sessionId);
			if (!Files.exists(file)) {
				return;
			}
			try {
				List<String> keep = new ArrayList<>();
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					try {
						SampleRecord s = Json.COMPACT.fromJson(line, SampleRecord.class);
						if (s != null && s.visitId != null && visitIds.contains(s.visitId)) {
							continue;
						}
					} catch (Exception ignored) {
					}
					if (!line.isBlank()) {
						keep.add(line);
					}
				}
				com.sessioniq.util.AtomicFiles.writeString(file, keep.isEmpty() ? "" : String.join("\n", keep) + "\n");
			} catch (IOException e) {
				storage.report("Could not rewrite samples of " + sessionId, StorageService.describe(e));
			}
		});
	}

	public void deleteSession(String sessionId) {
		if (sessionId.equals(pendingSession)) {
			pending.clear();
		}
		storage.runIo(() -> {
			try {
				Files.deleteIfExists(paths.samplesFile(sessionId));
			} catch (IOException e) {
				storage.report("Could not delete samples of " + sessionId, StorageService.describe(e));
			}
		});
	}
}
