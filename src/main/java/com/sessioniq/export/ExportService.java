package com.sessioniq.export;

import com.google.gson.stream.JsonWriter;
import com.sessioniq.SessionIQ;
import com.sessioniq.analytics.ChatSearch;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.analytics.StatsEngine;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SampleRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.track.ServerIdentity;
import com.sessioniq.util.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class ExportService {
	public enum Format {
		JSON("json"), CSV("csv"), TEXT("txt");

		public final String ext;

		Format(String ext) {
			this.ext = ext;
		}
	}

	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
	private final SessionIQ siq;

	public ExportService(SessionIQ siq) {
		this.siq = siq;
	}

	public Path destination() {
		String custom = siq.config.get().exportDir;
		if (custom != null && !custom.isBlank()) {
			try {
				return Path.of(custom);
			} catch (Exception ignored) {
			}
		}
		return siq.paths.exports;
	}

	private Path target(String kind, Format format) throws IOException {
		Path dir = destination();
		Files.createDirectories(dir);
		String base = "sessioniq-" + kind + "-" + FILE_STAMP.format(Instant.now());
		Path p = dir.resolve(base + "." + format.ext);
		int n = 1;
		while (Files.exists(p)) {
			p = dir.resolve(base + "-" + n++ + "." + format.ext);
		}
		return p;
	}

	private static String iso(Long millis) {
		return millis == null || millis <= 0 ? "" : ISO.format(Instant.ofEpochMilli(millis));
	}

	private void header(JsonWriter w, String format) throws IOException {
		w.name("format").value(format);
		w.name("version").value(1);
		w.name("exportedAt").value(System.currentTimeMillis());
		w.name("exportedAtIso").value(iso(System.currentTimeMillis()));
		w.name("generator").value("SessionIQ " + siq.modVersion);
	}

	private static JsonWriter json(Writer out) {
		JsonWriter w = new JsonWriter(out);
		w.setIndent("  ");
		w.setHtmlSafe(false);
		return w;
	}

	private void raw(JsonWriter w, Object value) throws IOException {
		w.jsonValue(Json.write(value, false));
	}

	public CompletableFuture<Path> sessions(List<SessionRecord> snapshot, Format format) {
		return siq.storage.submitQuery(() -> {
			Path p = target("sessions", format);
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				if (format == Format.JSON) {
					JsonWriter w = json(out);
					w.beginObject();
					header(w, "sessioniq-sessions");
					w.name("sessions").beginArray();
					for (SessionRecord s : snapshot) {
						raw(w, s);
					}
					w.endArray();
					w.endObject();
					w.flush();
				} else {
					Csv csv = new Csv(out);
					csv.row("session_id", "started_at", "started_at_ms", "ended_at", "ended_at_ms", "status", "type", "recovered", "application_ms",
						"playtime_ms", "active_ms", "multiplayer_ms", "singleplayer_ms", "minecraft_version", "mod_version", "visits", "unique_servers",
						"chat_messages", "disconnects", "unexpected_disconnects", "connection_attempts", "failed_connections", "reconnect_attempts",
						"memory_avg_mb", "memory_peak_mb", "memory_max_mb");
					for (SessionRecord s : snapshot) {
						csv.row(s.id, iso(s.startedAt), s.startedAt, iso(s.endedAt), s.endedAt == null ? "" : s.endedAt, s.status, s.type, s.recovered,
							s.runtimeMs, s.inWorldMs, s.activeMs, s.multiplayerMs, s.singleplayerMs, s.mcVersion, s.modVersion, s.visits.size(),
							s.uniqueServers(), s.chatCount, s.disconnects.size(), s.unexpectedDisconnects(), s.connectionAttempts, s.failedConnections,
							s.reconnectAttempts, s.memoryUsedMb == null ? "" : round(s.memoryUsedMb.avg()), s.memoryUsedMb == null ? "" : round(s.memoryUsedMb.max),
							s.memoryMaxMb == null ? "" : s.memoryMaxMb);
					}
				}
			}
			return p;
		});
	}

	public CompletableFuture<Path> session(SessionRecord s, Format format, Function<String, String> serverName) {
		siq.chat.flush();
		siq.samples.flush();
		return siq.storage.submitIo(() -> null).thenCompose(ignored -> siq.storage.submitQuery(() -> {
			Path p = target("session-" + s.id, format);
			List<ChatRecord> chat = siq.chat.readSession(s.id);
			chat.sort((a, b) -> Long.compare(a.at, b.at));
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				switch (format) {
					case JSON -> {
						List<SampleRecord> samples = siq.samples.read(s.id);
						JsonWriter w = json(out);
						w.beginObject();
						header(w, "sessioniq-session");
						w.name("session");
						raw(w, s);
						w.name("chat").beginArray();
						for (ChatRecord r : chat) {
							raw(w, r);
						}
						w.endArray();
						w.name("samples").beginArray();
						for (SampleRecord r : samples) {
							raw(w, r);
						}
						w.endArray();
						w.endObject();
						w.flush();
					}
					case CSV -> {
						Csv csv = new Csv(out);
						visitHeader(csv);
						for (VisitRecord v : s.visits) {
							visitRow(csv, s, v, serverName);
						}
					}
					case TEXT -> transcript(out, chat, "Session " + s.id, serverName);
				}
			}
			return p;
		}));
	}

	public CompletableFuture<Path> servers(List<SessionRecord> snapshot, Format format, Function<String, String> serverName,
										   Function<String, String> group) {
		boolean merge = siq.config.get().mergePorts;
		return siq.storage.submitQuery(() -> {
			Path p = target("servers", format);
			List<ServerAggregate> aggs = ServerAggregate.compute(snapshot, merge, null, ZoneOffset.UTC);
			aggs.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				if (format == Format.JSON) {
					JsonWriter w = json(out);
					w.beginObject();
					header(w, "sessioniq-servers");
					w.name("mergePorts").value(merge);
					w.name("servers").beginArray();
					for (ServerAggregate a : aggs) {
						w.beginObject();
						w.name("serverKey").value(a.key);
						w.name("kind").value(a.kind);
						w.name("name").value(a.name);
						w.name("nickname").value(serverName.apply(a.key));
						w.name("group").value(group.apply(a.key));
						w.name("address").value(a.address);
						w.name("totalMs").value(a.totalMs);
						w.name("visits").value(a.visits);
						w.name("longestVisitMs").value(a.longestMs);
						w.name("firstSeen").value(a.firstSeen);
						w.name("lastPlayed").value(a.lastPlayed);
						w.name("disconnects").value(a.disconnects);
						w.name("unexpectedDisconnects").value(a.unexpectedDisconnects);
						w.name("lastDisconnectReason").value(a.lastDisconnectReason);
						w.name("visitHistory").beginArray();
						for (SessionRecord s : snapshot) {
							for (VisitRecord v : s.visits) {
								if (a.key.equals(ServerIdentity.groupKey(v, merge))) {
									w.beginObject();
									w.name("sessionId").value(s.id);
									w.name("visit");
									raw(w, v);
									w.endObject();
								}
							}
						}
						w.endArray();
						w.endObject();
					}
					w.endArray();
					w.endObject();
					w.flush();
				} else {
					Csv csv = new Csv(out);
					csv.row("server_key", "kind", "name", "nickname", "group", "address", "total_ms", "visits", "longest_visit_ms", "first_seen",
						"last_played", "disconnects", "unexpected_disconnects", "last_disconnect_reason");
					for (ServerAggregate a : aggs) {
						csv.row(a.key, a.kind, a.name, serverName.apply(a.key), group.apply(a.key), a.address, a.totalMs, a.visits, a.longestMs,
							iso(a.firstSeen), iso(a.lastPlayed), a.disconnects, a.unexpectedDisconnects, a.lastDisconnectReason);
					}
				}
			}
			return p;
		});
	}

	public CompletableFuture<Path> visits(List<SessionRecord> snapshot, Format format, Function<String, String> serverName) {
		return siq.storage.submitQuery(() -> {
			Path p = target("visits", format);
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				if (format == Format.JSON) {
					JsonWriter w = json(out);
					w.beginObject();
					header(w, "sessioniq-visits");
					w.name("visits").beginArray();
					for (SessionRecord s : snapshot) {
						for (VisitRecord v : s.visits) {
							w.beginObject();
							w.name("sessionId").value(s.id);
							w.name("visit");
							raw(w, v);
							w.endObject();
						}
					}
					w.endArray();
					w.endObject();
					w.flush();
				} else {
					Csv csv = new Csv(out);
					visitHeader(csv);
					for (SessionRecord s : snapshot) {
						for (VisitRecord v : s.visits) {
							visitRow(csv, s, v, serverName);
						}
					}
				}
			}
			return p;
		});
	}

	private static void visitHeader(Csv csv) throws IOException {
		csv.row("session_id", "visit_id", "server_key", "kind", "name", "nickname", "address", "joined_at", "joined_at_ms", "left_at", "left_at_ms",
			"duration_ms", "paused_ms", "end_reason", "interrupted", "transfer", "chat_messages", "disconnect_id", "ping_min_ms", "ping_avg_ms",
			"ping_max_ms", "ping_samples");
	}

	private static void visitRow(Csv csv, SessionRecord s, VisitRecord v, Function<String, String> serverName) throws IOException {
		csv.row(s.id, v.id, v.serverKey, v.kind, v.name, serverName.apply(v.serverKey), v.address, iso(v.joinedAt), v.joinedAt, iso(v.leftAt),
			v.leftAt == null ? "" : v.leftAt, v.durationMs, v.pausedMs, v.endReason, v.interrupted, v.transfer, v.chatCount, v.disconnectId,
			v.ping == null ? "" : round(v.ping.min), v.ping == null ? "" : round(v.ping.avg()), v.ping == null ? "" : round(v.ping.max),
			v.ping == null ? 0 : v.ping.count);
	}

	public CompletableFuture<Path> chat(List<SessionRecord> snapshot, ChatSearch.Criteria criteria, Format format, Function<String, String> serverName) {
		siq.chat.flush();
		return siq.storage.submitIo(() -> null).thenCompose(ignored -> siq.storage.submitQuery(() -> {
			Path p = target("chat", format);
			ChatSearch.Criteria c = criteria.copy();
			c.newestFirst = false;
			ChatSearch search = new ChatSearch(siq.chat, snapshot, c);
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				switch (format) {
					case JSON -> {
						JsonWriter w = json(out);
						w.beginObject();
						header(w, "sessioniq-chat");
						w.name("messages").beginArray();
						while (!search.isExhausted()) {
							for (ChatRecord r : search.next(2000).items()) {
								raw(w, r);
							}
						}
						w.endArray();
						w.endObject();
						w.flush();
					}
					case CSV -> {
						Csv csv = new Csv(out);
						csv.row("message_id", "timestamp", "timestamp_ms", "session_id", "visit_id", "server_key", "server", "sender", "sender_uuid",
							"origin", "category", "message");
						while (!search.isExhausted()) {
							for (ChatRecord r : search.next(2000).items()) {
								csv.row(r.id, iso(r.at), r.at, r.sessionId, r.visitId, r.serverKey, serverName.apply(r.serverKey), r.sender, r.senderUuid,
									r.origin, ChatCategory.parse(r.category).label, r.text);
							}
						}
					}
					case TEXT -> {
						out.write("SessionIQ chat transcript\n");
						out.write("Exported " + local(System.currentTimeMillis()) + "\n\n");
						String lastServer = null;
						while (!search.isExhausted()) {
							for (ChatRecord r : search.next(2000).items()) {
								if (!java.util.Objects.equals(lastServer, r.serverKey)) {
									out.write("\n== " + serverName.apply(r.serverKey) + " ==\n");
									lastServer = r.serverKey;
								}
								out.write(line(r));
							}
						}
					}
				}
			}
			return p;
		}));
	}

	private void transcript(Writer out, List<ChatRecord> chat, String title, Function<String, String> serverName) throws IOException {
		out.write("SessionIQ chat transcript — " + title + "\n");
		out.write("Exported " + local(System.currentTimeMillis()) + "\n\n");
		String lastServer = null;
		for (ChatRecord r : chat) {
			if (!java.util.Objects.equals(lastServer, r.serverKey)) {
				out.write("\n== " + serverName.apply(r.serverKey) + " ==\n");
				lastServer = r.serverKey;
			}
			out.write(line(r));
		}
		if (chat.isEmpty()) {
			out.write("(no recorded messages)\n");
		}
	}

	private String line(ChatRecord r) {
		return "[" + local(r.at) + "] " + (r.isSystem() ? "[system] " : "") + r.text.replace("\n", "\n    ") + "\n";
	}

	private String local(long millis) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone()).format(Instant.ofEpochMilli(millis));
	}

	private ZoneId zone() {
		String z = siq.config.get().timeZone;
		try {
			return "system".equals(z) ? ZoneId.systemDefault() : ZoneId.of(z);
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}

	public CompletableFuture<Path> disconnects(List<SessionRecord> snapshot, Format format, Function<String, String> serverName) {
		return siq.storage.submitQuery(() -> {
			Path p = target("disconnects", format);
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				if (format == Format.JSON) {
					JsonWriter w = json(out);
					w.beginObject();
					header(w, "sessioniq-disconnects");
					w.name("disconnects").beginArray();
					for (SessionRecord s : snapshot) {
						for (DisconnectRecord d : s.disconnects) {
							raw(w, d);
						}
					}
					w.endArray();
					w.endObject();
					w.flush();
				} else {
					Csv csv = new Csv(out);
					csv.row("event_id", "timestamp", "timestamp_ms", "session_id", "visit_id", "server_key", "server", "address", "category", "certainty",
						"phase", "unexpected", "reason", "reason_key", "details", "evidence", "reconnect_attempt", "reconnect_attempts", "next_connection_at",
						"time_to_reconnect_ms", "returned_to_same_server", "next_visit_id", "recovered");
					for (SessionRecord s : snapshot) {
						for (DisconnectRecord d : s.disconnects) {
							csv.row(d.id, iso(d.at), d.at, d.sessionId, d.visitId, d.serverKey, serverName.apply(d.serverKey), d.address, d.category,
								d.certainty, d.phase, d.isUnexpected(), d.reason, d.reasonKey, d.details, String.join(" | ", d.evidence), d.reconnectAttempt,
								d.reconnectAttempts, iso(d.nextConnectionAt), d.timeToReconnectMs == null ? "" : d.timeToReconnectMs,
								d.returnedToSameServer == null ? "" : d.returnedToSameServer, d.nextVisitId, d.recovered);
						}
					}
				}
			}
			return p;
		});
	}

	public CompletableFuture<Path> statistics(StatsEngine.Stats st, Format format, Function<Long, String> duration, Function<String, String> serverName) {
		return siq.storage.submitQuery(() -> {
			Path p = target("statistics", format);
			try (BufferedWriter out = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				switch (format) {
					case JSON -> {
						JsonWriter w = json(out);
						w.beginObject();
						header(w, "sessioniq-statistics");
						w.name("range").value(st.range.describe());
						w.name("rangeFrom").value(st.firstDay == null ? null : st.firstDay.toString());
						w.name("rangeTo").value(st.lastDay == null ? null : st.lastDay.toString());
						w.name("timeZone").value(zone().getId());
						w.name("summary").beginObject();
						for (Map.Entry<String, Object> e : summary(st).entrySet()) {
							w.name(e.getKey());
							Object v = e.getValue();
							if (v == null) {
								w.nullValue();
							} else if (v instanceof Number n) {
								w.value(n);
							} else {
								w.value(String.valueOf(v));
							}
						}
						w.endObject();
						w.name("daily").beginArray();
						for (Map.Entry<LocalDate, long[]> e : st.daily.entrySet()) {
							w.beginObject().name("date").value(e.getKey().toString()).name("multiplayerMs").value(e.getValue()[0])
								.name("singleplayerMs").value(e.getValue()[1]).endObject();
						}
						w.endArray();
						w.name("servers").beginArray();
						for (ServerAggregate a : st.servers) {
							w.beginObject().name("serverKey").value(a.key).name("name").value(serverName.apply(a.key)).name("totalMs").value(a.totalMs)
								.name("visits").value(a.visits).endObject();
						}
						w.endArray();
						w.name("sessionDurationHistogram").beginArray();
						for (int i = 0; i < st.durationHistogram.length; i++) {
							w.beginObject().name("bucket").value(StatsEngine.DURATION_LABELS[i]).name("sessions").value(st.durationHistogram[i]).endObject();
						}
						w.endArray();
						w.endObject();
						w.flush();
					}
					case CSV -> {
						Csv csv = new Csv(out);
						csv.row("date", "multiplayer_ms", "singleplayer_ms", "total_ms", "unexpected_disconnects", "other_disconnects");
						for (Map.Entry<LocalDate, long[]> e : st.daily.entrySet()) {
							int[] d = st.disconnectsDaily.getOrDefault(e.getKey(), new int[2]);
							csv.row(e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[0] + e.getValue()[1], d[0], d[1]);
						}
					}
					case TEXT -> {
						out.write("SessionIQ statistics — " + st.range.describe() + " (" + zone().getId() + ")\n\n");
						for (Map.Entry<String, Object> e : summary(st).entrySet()) {
							Object v = e.getValue();
							String shown = v instanceof Long l && e.getKey().endsWith("Ms") ? duration.apply(l) : String.valueOf(v == null ? "—" : v);
							out.write(String.format("%-28s %s%n", e.getKey(), shown));
						}
						out.write("\nPlaytime by server\n");
						for (ServerAggregate a : st.servers) {
							out.write(String.format("  %-32s %10s  %d visit(s)%n", serverName.apply(a.key), duration.apply(a.totalMs), a.visits));
						}
					}
				}
			}
			return p;
		});
	}

	private static Map<String, Object> summary(StatsEngine.Stats st) {
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("playtimeMs", st.playtimeMs);
		m.put("activePlaytimeMs", st.activeMs);
		m.put("multiplayerMs", st.multiplayerMs);
		m.put("singleplayerMs", st.singleplayerMs);
		m.put("applicationMs", st.applicationMs);
		m.put("idleMs", st.idleMs);
		m.put("sessions", st.sessions);
		m.put("averageSessionMs", st.averageSessionMs);
		m.put("longestSessionMs", st.longestSession == null ? null : st.longestSession.runtimeMs);
		m.put("longestSessionId", st.longestSession == null ? null : st.longestSession.id);
		m.put("mostRecentSessionId", st.mostRecentSession == null ? null : st.mostRecentSession.id);
		m.put("serverVisits", st.multiplayerVisits);
		m.put("uniqueServers", st.uniqueServers);
		m.put("mostPlayedServer", st.mostPlayedServer == null ? null : st.mostPlayedServer.key);
		m.put("mostPlayedServerMs", st.mostPlayedServer == null ? null : st.mostPlayedServer.totalMs);
		m.put("longestVisitMs", st.longestVisit == null ? null : st.longestVisit.durationMs);
		m.put("longestVisitServer", st.longestVisit == null ? null : st.longestVisit.serverKey);
		m.put("chatMessages", st.chatMessages);
		m.put("disconnects", st.disconnects);
		m.put("unexpectedDisconnects", st.unexpectedDisconnects);
		return m;
	}

	private static double round(double v) {
		return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : 0;
	}

	static final class Csv {
		private final Writer out;

		Csv(Writer out) {
			this.out = out;
		}

		void row(Object... values) throws IOException {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(escape(values[i]));
			}
			sb.append("\r\n");
			out.write(sb.toString());
		}

		static String escape(Object v) {
			if (v == null) {
				return "";
			}
			String s = String.valueOf(v);
			if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
				return '"' + s.replace("\"", "\"\"") + '"';
			}
			return s;
		}
	}
}
