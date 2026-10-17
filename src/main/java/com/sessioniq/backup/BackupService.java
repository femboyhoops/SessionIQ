package com.sessioniq.backup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.storage.DataPaths;
import com.sessioniq.storage.Migrations;
import com.sessioniq.storage.StorageService;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class BackupService {
	public static final String MANIFEST = "sessioniq-backup.json";
	public static final int FORMAT = 1;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final List<String> INCLUDED_ROOTS = List.of("sessions", "chat", "samples", "servers", "manifest.json", "themes.json", "config.json");
	private static final Set<String> RESTORABLE_ROOTS = Set.of("sessions", "chat", "samples", "servers");

	private final DataPaths paths;
	private final StorageService storage;
	private final String modVersion;

	public BackupService(DataPaths paths, StorageService storage, String modVersion) {
		this.paths = paths;
		this.storage = storage;
		this.modVersion = modVersion;
	}

	public record BackupInfo(Path file, String name, long createdAt, boolean auto, String label, int schema, int fileCount, long size, String problem) {
	}

	public record VerifyResult(boolean ok, List<String> problems, int fileCount, int sessionCount, int schema, long createdAt) {
	}

	public Path createNow(String label, boolean auto) throws IOException {
		Files.createDirectories(paths.backups);
		long now = System.currentTimeMillis();
		String name = "sessioniq-" + (auto ? "auto" : "manual") + "-" + STAMP.format(Instant.ofEpochMilli(now)) + ".zip";
		Path target = paths.backups.resolve(name);
		int n = 1;
		while (Files.exists(target)) {
			target = paths.backups.resolve(name.replace(".zip", "-" + n++ + ".zip"));
		}
		Path temp = target.resolveSibling(target.getFileName() + ".partial");
		List<Path> files = collect();
		JsonArray list = new JsonArray();
		byte[] buffer = new byte[64 * 1024];
		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
			for (Path file : files) {
				String rel = relative(file);
				MessageDigest sha = sha256();
				long size = 0;
				zip.putNextEntry(new ZipEntry(rel));
				try (InputStream in = Files.newInputStream(file)) {
					int read;
					while ((read = in.read(buffer)) > 0) {
						sha.update(buffer, 0, read);
						zip.write(buffer, 0, read);
						size += read;
					}
				}
				zip.closeEntry();
				JsonObject entry = new JsonObject();
				entry.addProperty("path", rel);
				entry.addProperty("size", size);
				entry.addProperty("sha256", HexFormat.of().formatHex(sha.digest()));
				list.add(entry);
			}
			JsonObject manifest = new JsonObject();
			manifest.addProperty("format", FORMAT);
			manifest.addProperty("schema", Migrations.CURRENT);
			manifest.addProperty("createdAt", now);
			manifest.addProperty("createdBy", modVersion);
			manifest.addProperty("auto", auto);
			manifest.addProperty("label", label == null ? "" : label);
			manifest.add("files", list);
			zip.putNextEntry(new ZipEntry(MANIFEST));
			zip.write(Json.PRETTY.toJson(manifest).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (IOException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
		AtomicFiles.move(temp, target);
		return target;
	}

	private List<Path> collect() throws IOException {
		List<Path> out = new ArrayList<>();
		for (String rootName : INCLUDED_ROOTS) {
			Path p = paths.root.resolve(rootName);
			if (Files.isRegularFile(p)) {
				out.add(p);
			} else if (Files.isDirectory(p)) {
				try (Stream<Path> walk = Files.walk(p)) {
					walk.filter(Files::isRegularFile)
						.filter(f -> {
							String n = f.getFileName().toString();
							return !n.endsWith(".tmp") && !n.endsWith(".partial");
						})
						.sorted()
						.forEach(out::add);
				}
			}
		}
		return out;
	}

	private String relative(Path file) {
		return paths.root.relativize(file).toString().replace('\\', '/');
	}

	public List<String> rotate(int maxAuto) throws IOException {
		List<String> removed = new ArrayList<>();
		List<BackupInfo> autos = list().stream().filter(BackupInfo::auto).sorted(Comparator.comparingLong(BackupInfo::createdAt).reversed()).toList();
		for (int i = maxAuto; i < autos.size(); i++) {
			Files.deleteIfExists(autos.get(i).file());
			removed.add(autos.get(i).name());
		}
		return removed;
	}

	public List<BackupInfo> list() {
		List<BackupInfo> out = new ArrayList<>();
		if (!Files.isDirectory(paths.backups)) {
			return out;
		}
		try (Stream<Path> s = Files.list(paths.backups)) {
			for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".zip")).toList()) {
				out.add(describe(p));
			}
		} catch (IOException ignored) {
		}
		out.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed());
		return out;
	}

	private BackupInfo describe(Path p) {
		String name = p.getFileName().toString();
		long size = 0;
		try {
			size = Files.size(p);
		} catch (IOException ignored) {
		}
		try (ZipFile zip = new ZipFile(p.toFile())) {
			JsonObject m = readManifest(zip);
			if (m == null) {
				return new BackupInfo(p, name, lastModified(p), name.contains("-auto-"), "", 0, 0, size, "No manifest");
			}
			return new BackupInfo(p, name, m.get("createdAt").getAsLong(), m.has("auto") && m.get("auto").getAsBoolean(),
				m.has("label") ? m.get("label").getAsString() : "", m.get("schema").getAsInt(), m.getAsJsonArray("files").size(), size, null);
		} catch (Exception e) {
			return new BackupInfo(p, name, lastModified(p), name.contains("-auto-"), "", 0, 0, size, "Unreadable archive: " + e.getMessage());
		}
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static JsonObject readManifest(ZipFile zip) throws IOException {
		ZipEntry entry = zip.getEntry(MANIFEST);
		if (entry == null) {
			return null;
		}
		try (InputStream in = zip.getInputStream(entry)) {
			JsonElement e = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			return e.getAsJsonObject();
		}
	}

	public VerifyResult verify(Path archive) {
		List<String> problems = new ArrayList<>();
		int files = 0;
		int sessions = 0;
		int schema = 0;
		long createdAt = 0;
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			JsonObject m = readManifest(zip);
			if (m == null) {
				return new VerifyResult(false, List.of("The archive has no SessionIQ manifest"), 0, 0, 0, 0);
			}
			if (m.get("format").getAsInt() > FORMAT) {
				problems.add("The backup format is newer than this version of SessionIQ supports");
			}
			schema = m.get("schema").getAsInt();
			createdAt = m.get("createdAt").getAsLong();
			if (schema > Migrations.CURRENT) {
				problems.add("The backup uses storage schema " + schema + ", newer than supported (" + Migrations.CURRENT + ")");
			}
			byte[] buffer = new byte[64 * 1024];
			for (JsonElement el : m.getAsJsonArray("files")) {
				JsonObject f = el.getAsJsonObject();
				String path = f.get("path").getAsString();
				if (!safeEntry(path)) {
					problems.add("Unsafe path in archive: " + path);
					continue;
				}
				ZipEntry entry = zip.getEntry(path);
				if (entry == null) {
					problems.add("Missing file: " + path);
					continue;
				}
				MessageDigest sha = sha256();
				long size = 0;
				byte[] content = null;
				boolean parseJson = path.endsWith(".json");
				try (InputStream in = zip.getInputStream(entry)) {
					java.io.ByteArrayOutputStream keep = parseJson ? new java.io.ByteArrayOutputStream() : null;
					int read;
					while ((read = in.read(buffer)) > 0) {
						sha.update(buffer, 0, read);
						size += read;
						if (keep != null) {
							keep.write(buffer, 0, read);
						}
					}
					if (keep != null) {
						content = keep.toByteArray();
					}
				}
				if (size != f.get("size").getAsLong() || !HexFormat.of().formatHex(sha.digest()).equals(f.get("sha256").getAsString())) {
					problems.add("Checksum mismatch: " + path);
					continue;
				}
				if (content != null) {
					try {
						JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
					} catch (Exception e) {
						problems.add("Invalid JSON: " + path);
					}
				}
				files++;
				if (path.startsWith("sessions/")) {
					sessions++;
				}
			}
		} catch (Exception e) {
			problems.add("The archive could not be read: " + e.getMessage());
		}
		return new VerifyResult(problems.isEmpty(), problems, files, sessions, schema, createdAt);
	}

	static boolean safeEntry(String path) {
		if (path.contains("..") || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
			return false;
		}
		int slash = path.indexOf('/');
		String root = slash < 0 ? path : path.substring(0, slash);
		return INCLUDED_ROOTS.contains(root);
	}

	public enum RestoreMode {
		REPLACE,
		MERGE
	}

	public record RestoreResult(int sessionsRestored, int sessionsSkipped, String safetyBackup) {
	}

	public RestoreResult restore(Path archive, RestoreMode mode, String protectedSessionId, Set<String> existingSessionIds) throws IOException {
		VerifyResult v = verify(archive);
		if (!v.ok()) {
			throw new IOException("Backup failed verification: " + String.join("; ", v.problems()));
		}
		String safety = createNow("pre-restore", false).getFileName().toString();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			JsonObject m = readManifest(zip);
			List<String> entries = new ArrayList<>();
			for (JsonElement el : m.getAsJsonArray("files")) {
				entries.add(el.getAsJsonObject().get("path").getAsString());
			}
			Map<String, String> sessionOfEntry = new HashMap<>();
			Set<String> backupSessions = new HashSet<>();
			for (String e : entries) {
				String sid = sessionIdOf(e);
				if (sid != null) {
					sessionOfEntry.put(e, sid);
					if (e.startsWith("sessions/")) {
						backupSessions.add(sid);
					}
				}
			}
			int restored = 0;
			int skipped = 0;
			if (mode == RestoreMode.REPLACE) {
				for (String root : RESTORABLE_ROOTS) {
					clearExcept(paths.root.resolve(root), protectedSessionId);
				}
			}
			for (String e : entries) {
				String root = e.contains("/") ? e.substring(0, e.indexOf('/')) : e;
				if (!RESTORABLE_ROOTS.contains(root)) {
					continue;
				}
				String sid = sessionOfEntry.get(e);
				if (sid != null && sid.equals(protectedSessionId)) {
					continue;
				}
				if (mode == RestoreMode.MERGE) {
					if (sid != null && existingSessionIds.contains(sid)) {
						continue;
					}
					if (e.equals("servers/registry.json")) {
						mergeRegistry(zip, e);
						continue;
					}
				}
				Path target = paths.root.resolve(e).normalize();
				if (!target.startsWith(paths.root)) {
					continue;
				}
				Files.createDirectories(target.getParent());
				try (InputStream in = zip.getInputStream(zip.getEntry(e)); OutputStream out = Files.newOutputStream(target)) {
					in.transferTo(out);
				}
			}
			for (String sid : backupSessions) {
				if (sid.equals(protectedSessionId) || mode == RestoreMode.MERGE && existingSessionIds.contains(sid)) {
					skipped++;
				} else {
					restored++;
				}
			}
			return new RestoreResult(restored, skipped, safety);
		}
	}

	private void mergeRegistry(ZipFile zip, String entryName) throws IOException {
		JsonObject incoming;
		try (InputStream in = zip.getInputStream(zip.getEntry(entryName))) {
			incoming = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
		JsonObject current = new JsonObject();
		if (Files.exists(paths.registry)) {
			try {
				current = JsonParser.parseString(Files.readString(paths.registry, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception ignored) {
			}
		}
		JsonObject cur = current.has("servers") ? current.getAsJsonObject("servers") : new JsonObject();
		JsonObject inc = incoming.has("servers") ? incoming.getAsJsonObject("servers") : new JsonObject();
		for (Map.Entry<String, JsonElement> e : inc.entrySet()) {
			if (!cur.has(e.getKey())) {
				cur.add(e.getKey(), e.getValue());
			}
		}
		current.addProperty("schema", 1);
		current.add("servers", cur);
		AtomicFiles.writeString(paths.registry, Json.PRETTY.toJson(current));
	}

	static String sessionIdOf(String entry) {
		String[] parts = entry.split("/");
		if (parts.length >= 3 && parts[0].equals("sessions") && parts[2].endsWith(".json")) {
			return parts[2].substring(0, parts[2].length() - 5);
		}
		if (parts.length >= 2 && parts[0].equals("chat")) {
			return parts[1];
		}
		if (parts.length == 2 && parts[0].equals("samples") && parts[1].endsWith(".jsonl")) {
			return parts[1].substring(0, parts[1].length() - 6);
		}
		return null;
	}

	private void clearExcept(Path dir, String protectedSessionId) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		List<Path> all;
		try (Stream<Path> walk = Files.walk(dir)) {
			all = walk.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path p : all) {
			if (p.equals(dir)) {
				continue;
			}
			if (Files.isRegularFile(p)) {
				String sid = sessionIdOf(relative(p));
				if (protectedSessionId != null && protectedSessionId.equals(sid)) {
					continue;
				}
				Files.deleteIfExists(p);
			} else if (Files.isDirectory(p)) {
				try (Stream<Path> children = Files.list(p)) {
					if (children.findAny().isEmpty()) {
						Files.deleteIfExists(p);
					}
				}
			}
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public StorageService storage() {
		return storage;
	}
}
