package com.sessioniq.gui.pages;

import com.sessioniq.analytics.TimelineBuilder;
import com.sessioniq.analytics.TimelineEvent;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Charts;
import com.sessioniq.gui.ExportModal;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MarkerRecord;
import com.sessioniq.model.SampleRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.DataActions;
import net.minecraft.text.OrderedText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SessionDetailPage extends Page {
	private static final String[] TABS = {"Overview", "Servers", "Chat", "Disconnects", "Timeline", "Performance"};

	private final String sessionId;
	private int tab;
	private final Async<List<ChatRecord>> chat = new Async<>();
	private final Async<List<SampleRecord>> samples = new Async<>();
	private final TimelineView timeline;
	private long lastChatLoad;

	public SessionDetailPage(SiqScreen screen, String sessionId) {
		super(screen);
		this.sessionId = sessionId;
		this.timeline = new TimelineView("sd#tl" + sessionId);
	}

	private SessionRecord session() {
		return siq.sessions.get(sessionId);
	}

	@Override
	public String title() {
		SessionRecord s = session();
		return s == null ? "Session" : "Session · " + Fmt.dateTime(s.startedAt);
	}

	@Override
	public String crumb() {
		SessionRecord s = session();
		return s == null ? "Session" : Fmt.date(s.startedAt);
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		SessionRecord s = session();
		if (s == null) {
			return right;
		}
		int x = right;
		if (!s.isActive()) {
			int dw = ui.buttonWidth("Delete", Icons.TRASH);
			x -= dw;
			if (ui.button("sd#delete", x, y, dw, h, "Delete", Icons.TRASH, Ui.Btn.DANGER, true)) {
				screen.confirm("Delete this session?",
					"This permanently deletes the session from " + Fmt.dateTime(s.startedAt) + ": " + Fmt.plural(s.visits.size(), "visit", "visits") + ", "
						+ Fmt.plural(s.disconnects.size(), "disconnect event", "disconnect events") + ", " + Fmt.plural(s.chatCount, "chat message", "chat messages")
						+ " and its ping and memory samples. Statistics will no longer include it. Backups are not affected.",
					"Delete session", true, () -> {
						DataActions.deleteSession(siq, sessionId);
						screen.back();
						screen.toast("Session deleted", SiqScreen.TOAST_SUCCESS);
					});
			}
			x -= 6;
		}
		int ew = ui.buttonWidth("Export", Icons.EXPORT);
		x -= ew;
		if (ui.button("sd#export", x, y, ew, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, true)) {
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Full session", "Session record with its visits, disconnect events, chat messages and samples.",
					List.of(Format.JSON), f -> siq.exports.session(snapshot(), f, ServerNames::name)),
				new ExportModal.Option("Visits table", "One row per server visit in this session.",
					List.of(Format.CSV), f -> siq.exports.session(snapshot(), f, ServerNames::name)),
				new ExportModal.Option("Chat transcript", "Readable transcript of the chat recorded in this session.",
					List.of(Format.TEXT), f -> siq.exports.session(snapshot(), f, ServerNames::name))
			)));
		}
		return x - 6;
	}

	private SessionRecord snapshot() {
		SessionRecord s = session();
		return s.isActive() ? com.sessioniq.util.Json.copy(s, SessionRecord.class) : s;
	}

	static final int INLINE_CHAT_LIMIT = 30_000;

	private void loadChat(SessionRecord s) {
		if (s.chatCount > INLINE_CHAT_LIMIT) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean stale = s.isActive() && now - lastChatLoad > 3000;
		Object key = s.id + "|" + s.chatCount;
		if (stale || !chat.hasValue() && !chat.loading()) {
			lastChatLoad = now;
			siq.chat.flush();
			chat.reload(key, () -> siq.storage.submitIo(() -> null).thenCompose(v -> siq.storage.submitQuery(() -> {
				List<ChatRecord> list = siq.chat.readSession(sessionId);
				list.sort((a, b) -> Long.compare(a.at, b.at));
				return list;
			})));
		}
	}

	@Override
	public void onShow() {
		chat.invalidate();
		samples.invalidate();
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		SessionRecord s = session();
		int pad = ui.pad() + 4;
		if (s == null) {
			if (ui.emptyState("sd#missing", x, y, w, h, Icons.SESSIONS, siq.isReady() ? "Session not found" : "Loading…",
				siq.isReady() ? "This session no longer exists. It may have been deleted." : "History is still loading.", siq.isReady() ? "Back" : null)) {
				screen.back();
			}
			return;
		}
		int cx = x + pad;
		int cw = w - pad * 2;
		int ty = y + 6;
		int sel = ui.tabs("sd#tabs", cx, ty, cw, TABS, tab);
		if (sel >= 0) {
			tab = sel;
		}
		int bodyY = ty + ui.rowH() + 1;
		int bodyH = y + h - bodyY;
		String sid = "sd#body" + tab;
		int off = ui.beginScroll(sid, x, bodyY, w, bodyH);
		int cy = bodyY + pad - off;
		int used = switch (tab) {
			case 1 -> renderServers(ui, s, cx, cy, cw);
			case 2 -> renderChat(ui, s, cx, cy, cw);
			case 3 -> renderDisconnects(ui, s, cx, cy, cw);
			case 4 -> renderTimeline(ui, s, cx, cy, cw);
			case 5 -> renderPerformance(ui, s, cx, cy, cw);
			default -> renderOverview(ui, s, cx, cy, cw);
		};
		ui.endScroll(sid, used + pad * 2);
	}

	private int renderOverview(Ui ui, SessionRecord s, int x, int y, int w) {
		int cy = y;
		if (s.recovered) {
			int ph = ui.paragraphHeight(s.recoveryNote, w - 40) + 16;
			ui.panel(x, cy, w, ph, Ui.argb(Ui.mix(ui.th.surface, ui.th.warning, 0.12f), 1f), Ui.withAlpha(ui.warning, 0.5f), ui.radius());
			ui.icon(Icons.WARNING, x + 10, cy + 8, ui.warning);
			ui.paragraph(s.recoveryNote, x + 26, cy + 8, w - 40, ui.text);
			cy += ph + ui.gap();
		}
		long deaths = s.markers.stream().filter(m -> MarkerRecord.TYPE_DEATH.equals(m.type)).count();
		String[][] left = {
			{"Started", Fmt.dateTimeSeconds(s.startedAt)},
			{"Ended", s.isActive() ? "Still recording" : s.endedAt == null ? "Unknown" : Fmt.dateTimeSeconds(s.endedAt)},
			{"Application time", Fmt.duration(s.runtimeMs)},
			{"Playtime (in a world)", Fmt.duration(s.inWorldMs)},
			{"Active playtime (not paused)", Fmt.duration(s.activeMs)},
			{"Multiplayer connected", Fmt.duration(s.multiplayerMs)},
			{"Single-player", Fmt.duration(s.singleplayerMs)},
			{"Idle (menus, loading)", Fmt.duration(Math.max(0, s.runtimeMs - s.inWorldMs))},
		};
		String[][] right = {
			{"Status", Widgets.statusLabel(s) + (s.endNote != null ? " · " + s.endNote : "")},
			{"Session type", Fmt.sessionType(s.type)},
			{"Minecraft version", s.mcVersion == null ? "Unknown" : s.mcVersion},
			{"SessionIQ version", s.modVersion == null ? "Unknown" : s.modVersion},
			{"Servers visited", Fmt.count(s.uniqueServers()) + " (" + Fmt.plural(s.multiplayerVisits(), "visit", "visits") + ")"},
			{"Chat messages", Fmt.count(s.chatCount)},
			{"Disconnects", s.disconnects.size() + " (" + s.unexpectedDisconnects() + " unexpected)"},
			{"Connection attempts", s.connectionAttempts + " · " + s.failedConnections + " failed · " + s.reconnectAttempts + " reconnects"},
			{"Deaths", Fmt.count(deaths)},
		};
		boolean two = w >= 520;
		int colW = two ? (w - ui.gap()) / 2 : w;
		int rowH = ui.rowH();
		int lh = left.length * rowH + 16;
		int rh = right.length * rowH + 16;
		ui.card(x, cy, colW, lh);
		for (int i = 0; i < left.length; i++) {
			Widgets.kv(ui, x + 12, cy + 8 + i * rowH + (rowH - ui.capH()) / 2, colW - 24, left[i][0], left[i][1], ui.text);
		}
		int rx = two ? x + colW + ui.gap() : x;
		int ry = two ? cy : cy + lh + ui.gap();
		ui.card(rx, ry, colW, rh);
		for (int i = 0; i < right.length; i++) {
			Widgets.kv(ui, rx + 12, ry + 8 + i * rowH + (rowH - ui.capH()) / 2, colW - 24, right[i][0], right[i][1],
				i == 0 ? Widgets.statusColor(ui, s) : ui.text);
		}
		cy += two ? Math.max(lh, rh) : lh + ui.gap() + rh;
		cy += ui.gap();
		ui.paragraph("Playtime counts time connected to a server or inside a single-player world. Application time is how long Minecraft ran; idle time is the difference.",
			x, cy, w, ui.text3);
		cy += ui.paragraphHeight("Playtime counts time connected to a server or inside a single-player world. Application time is how long Minecraft ran; idle time is the difference.", w);
		return cy - y;
	}

	private int renderServers(Ui ui, SessionRecord s, int x, int y, int w) {
		int cy = y;
		if (s.visits.isEmpty()) {
			ui.emptyState("sd#novisits", x, cy, w, 160, Icons.SERVERS, "No servers or worlds", "No server or world was joined during this session.", null);
			return 160;
		}
		Map<String, Long> perServer = new LinkedHashMap<>();
		for (VisitRecord v : s.visits) {
			perServer.merge(v.serverKey, v.durationMs, Long::sum);
		}
		List<Map.Entry<String, Long>> entries = new ArrayList<>(perServer.entrySet());
		entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		int rowH = ui.rowH();
		int boxH = 34 + entries.size() * rowH + 8;
		ui.card(x, cy, w, boxH);
		Widgets.heading(ui, x + 12, cy + 10, w - 24, "Time per server", Fmt.duration(s.inWorldMs) + " total");
		int clicked = Charts.hbars(ui, "sd#per", x + 12, cy + 30, w - 24, entries.stream().map(e -> ServerNames.name(e.getKey())).toList(),
			entries.stream().mapToLong(Map.Entry::getValue).toArray(), ui.accent, Fmt::duration, rowH, null);
		if (clicked >= 0) {
			screen.push(new ServerDetailPage(screen, entries.get(clicked).getKey()));
		}
		cy += boxH + ui.gap();

		int rh = ui.rowH() + 16;
		int listH = 30 + s.visits.size() * rh + 6;
		ui.card(x, cy, w, listH);
		Widgets.heading(ui, x + 12, cy + 10, w - 24, "Visits", Fmt.plural(s.visits.size(), "visit", "visits"));
		int ry = cy + 28;
		boolean wide = w >= 560;
		for (VisitRecord v : s.visits) {
			if (Widgets.row(ui, "sd#visit" + v.id, x + 1, ry, w - 2, rh, false)) {
				screen.push(new ServerDetailPage(screen, v.serverKey));
			}
			ui.icon(v.isMultiplayer() ? Icons.SERVERS : Icons.GLOBE, x + 12, ry + 5, v.open ? ui.success : ui.text2);
			ui.textClip(ServerNames.name(v.serverKey), x + 28, ry + 4, w / 2 - 40, ui.text);
			String times = Fmt.timeSeconds(v.joinedAt) + " – " + (v.open ? "now" : v.leftAt == null ? "?" : Fmt.timeSeconds(v.leftAt));
			ui.textClip(times, x + 28, ry + 6 + ui.lineH(), w / 2 - 40, ui.text3);
			String dur = Fmt.duration(v.durationMs) + (v.pausedMs > 0 ? " (" + Fmt.duration(v.pausedMs) + " paused)" : "");
			ui.textRight(dur, x + w - 12, ry + 4, ui.text);
			String endText = endLabel(s, v);
			String extra = v.chatCount + " chat" + (wide && v.ping != null && v.ping.count > 0 ? " · ping " + Fmt.ms(v.ping.avg()) : "");
			ui.textRight(ui.ellipsize(endText + " · " + extra, w / 2 - 20), x + w - 12, ry + 6 + ui.lineH(), v.interrupted ? ui.warning : ui.text3);
			ry += rh;
		}
		cy += listH;
		return cy - y;
	}

	static String endLabel(SessionRecord s, VisitRecord v) {
		if (v.open) {
			return "In progress";
		}
		DisconnectRecord d = v.disconnectId == null ? null : s.disconnect(v.disconnectId);
		if (d != null) {
			return d.categoryEnum().label;
		}
		return switch (v.endReason == null ? "" : v.endReason) {
			case VisitRecord.END_GAME_CLOSED -> "Game closed";
			case VisitRecord.END_RECORDING_STOPPED -> "Recording stopped";
			case VisitRecord.END_INTERRUPTED -> "Interrupted";
			default -> "Left";
		};
	}

	private int renderChat(Ui ui, SessionRecord s, int x, int y, int w) {
		loadChat(s);
		int cy = y;
		int bh = ui.buttonH() + 2;
		int linkW = ui.buttonWidth("Open in Chat History", Icons.CHAT);
		TextField search = ui.textField("sd#chatsearch", x, cy, w - linkW - ui.gap(), bh, "Search this session's chat…", Icons.SEARCH);
		search.consumeChanged();
		if (ui.button("sd#openchat", x + w - linkW, cy, linkW, bh, "Open in Chat History", Icons.CHAT, Ui.Btn.SECONDARY, true)) {
			ChatPage page = new ChatPage(screen);
			page.presetSession(sessionId, Long.MIN_VALUE, Long.MAX_VALUE);
			screen.push(page);
		}
		cy += bh + ui.gap();
		if (s.chatCount > INLINE_CHAT_LIMIT) {
			ui.paragraph("This session has " + Fmt.count(s.chatCount) + " messages, too many to show here at once. Use Open in Chat History to browse and search them page by page.",
				x, cy, w, ui.text2);
			return cy - y + 40;
		}
		List<ChatRecord> list = chat.get();
		if (list == null) {
			ui.loading(x, cy, w, 80, "Loading chat…");
			return cy - y + 80;
		}
		String q = search.text.trim().toLowerCase(Locale.ROOT);
		List<ChatRecord> shown = q.isEmpty() ? list : list.stream().filter(r -> r.text.toLowerCase(Locale.ROOT).contains(q)
			|| r.sender != null && r.sender.toLowerCase(Locale.ROOT).contains(q)).toList();
		ui.text(Fmt.plural(shown.size(), "message", "messages") + (q.isEmpty() ? "" : " match"), x, cy, ui.text3);
		cy += ui.lineH() + 6;
		if (shown.isEmpty()) {
			ui.emptyState("sd#nochat", x, cy, w, 140, Icons.CHAT, q.isEmpty() ? "No chat recorded" : "No matches",
				q.isEmpty() ? (siq.config.get().recordChat ? "No chat messages were received during this session." : "Chat recording is turned off in Settings.")
					: "No message in this session contains \"" + search.text + "\".", null);
			return cy - y + 140;
		}
		int textX = x + 12 + ui.textW("00:00:00 PM") + 8;
		int textW = x + w - textX - 10;
		int lh = ui.lineH() + 2;
		for (ChatRecord r : shown) {
			List<OrderedText> lines = ui.wrap(r.text, textW);
			int rh = lines.size() * lh + 6;
			if (ui.visible(cy, rh)) {
				if (Widgets.row(ui, "sd#msg" + r.id, x, cy, w, rh, false)) {
					screen.push(new ChatMessagePage(screen, r));
				}
				if (r.isSystem()) {
					ui.fill(x + 2, cy + 2, 2, rh - 4, Ui.withAlpha(ui.text3, 0.6f));
				}
				ui.text(Fmt.timeSeconds(r.at), x + 10, cy + 3, ui.text3);
				for (int i = 0; i < lines.size(); i++) {
					ui.text(lines.get(i), textX, cy + 3 + i * lh, r.isSystem() ? ui.text2 : ui.text);
				}
			}
			cy += rh;
		}
		return cy - y;
	}

	private int renderDisconnects(Ui ui, SessionRecord s, int x, int y, int w) {
		int cy = y;
		if (s.disconnects.isEmpty()) {
			ui.emptyState("sd#nodc", x, cy, w, 160, Icons.DISCONNECTS, "No disconnects", "No connection ended or failed during this session.", null);
			return 160;
		}
		long unexpected = s.unexpectedDisconnects();
		ui.text(Fmt.plural(s.disconnects.size(), "event", "events") + " · " + unexpected + " unexpected", x, cy, ui.text2);
		cy += ui.lineH() + 8;
		List<DisconnectRecord> list = new ArrayList<>(s.disconnects);
		list.sort((a, b) -> Long.compare(a.at, b.at));
		int rh = ui.rowH() * 2 + 6;
		ui.card(x, cy, w, list.size() * rh + 4);
		int ry = cy + 2;
		for (DisconnectRecord d : list) {
			if (Widgets.row(ui, "sd#dc" + d.id, x + 1, ry, w - 2, rh, false)) {
				screen.push(new DisconnectDetailPage(screen, sessionId, d.id));
			}
			renderDisconnectRow(ui, d, x + 10, ry, w - 20, rh);
			ry += rh;
		}
		return ry - y + 4;
	}

	static void renderDisconnectRow(Ui ui, DisconnectRecord d, int x, int y, int w, int h) {
		int color = Widgets.categoryColor(ui, d.categoryEnum());
		ui.icon(d.isUnexpected() ? Icons.WARNING : Icons.LEAVE, x, y + 6, color);
		ui.badge(x + 14, y + 4, d.categoryEnum().label, color);
		int bx = x + 14 + ui.badgeW(d.categoryEnum().label) + 6;
		ui.textClip(ServerNames.name(d.serverKey), bx, y + 6, w / 2, ui.text);
		ui.textRight(Fmt.dateTimeSeconds(d.at), x + w, y + 6, ui.text3);
		String reason = d.reason != null ? d.reason : "No reason was available";
		String second = Widgets.certaintyLabel(d) + " · " + reason;
		ui.textClip(second, x + 14, y + 10 + ui.lineH(), w - 140, ui.text2);
		ui.textRight(Widgets.reconnectSummary(d), x + w, y + 10 + ui.lineH(), ui.text3);
	}

	private int renderTimeline(Ui ui, SessionRecord s, int x, int y, int w) {
		loadChat(s);
		List<ChatRecord> list = chat.get();
		List<TimelineEvent> events = TimelineBuilder.build(s, list, Fmt::duration, ServerNames::name);
		Map<String, List<ChatRecord>> map = new java.util.HashMap<>();
		if (list != null) {
			map.put(s.id, list);
		}
		int cy = y;
		if (s.chatCount > INLINE_CHAT_LIMIT) {
			ui.text("Chat activity is omitted for sessions with more than " + Fmt.count(INLINE_CHAT_LIMIT) + " messages.", x, cy, ui.text3);
			cy += ui.lineH() + 6;
		} else if (list == null) {
			ui.text("Chat activity is loading…", x, cy, ui.text3);
			cy += ui.lineH() + 6;
		}
		cy += timeline.render(ui, screen, x, cy, w, events, map, sessionId);
		return cy - y;
	}

	private int renderPerformance(Ui ui, SessionRecord s, int x, int y, int w) {
		siq.samples.flush();
		samples.ensure(s.id + "|" + (s.isActive() ? System.currentTimeMillis() / 10000 : 0),
			() -> siq.storage.submitIo(() -> null).thenCompose(v -> siq.storage.submitQuery(() -> siq.samples.read(sessionId))));
		int cy = y;
		List<VisitRecord> mp = s.visits.stream().filter(VisitRecord::isMultiplayer).toList();
		int rowH = ui.rowH();
		int pingBoxH = 34 + Math.max(1, mp.size()) * rowH + 10;
		ui.card(x, cy, w, pingBoxH);
		Widgets.heading(ui, x + 12, cy + 10, w - 24, "Ping by visit", "server-reported latency");
		int ry = cy + 30;
		if (mp.isEmpty()) {
			ui.text("No multiplayer visits in this session, so there is no ping to show.", x + 12, ry + 4, ui.text3);
		}
		for (VisitRecord v : mp) {
			ui.textMid(ui.ellipsize(ServerNames.name(v.serverKey) + " · " + Fmt.time(v.joinedAt), w / 2 - 20), x + 12, ry, rowH, ui.text);
			String val;
			if (v.ping == null || v.ping.count == 0) {
				val = siq.config.get().recordPing ? "Unavailable (server did not report ping)" : "Not recorded (ping sampling off)";
			} else {
				val = "min " + Fmt.ms(v.ping.min) + " · avg " + Fmt.ms(v.ping.avg()) + " · max " + Fmt.ms(v.ping.max) + " · " + v.ping.count + " samples";
			}
			ui.textMid(ui.ellipsize(val, w / 2), x + w - 12 - Math.min(ui.textW(val), w / 2), ry, rowH, v.ping == null ? ui.text3 : ui.text2);
			ry += rowH;
		}
		cy += pingBoxH + ui.gap();

		List<SampleRecord> all = samples.get();
		int chartH = 130;
		for (int pass = 0; pass < 2; pass++) {
			boolean ping = pass == 0;
			String title = ping ? "Ping over time" : "Memory usage";
			ui.card(x, cy, w, chartH + 44);
			String note;
			if (ping) {
				note = "ms";
			} else if (s.memoryUsedMb != null && s.memoryUsedMb.count > 0) {
				note = "avg " + Fmt.mb(s.memoryUsedMb.avg()) + " · peak " + Fmt.mb(s.memoryUsedMb.max) + (s.memoryMaxMb != null ? " · max heap " + s.memoryMaxMb + " MB" : "");
			} else {
				note = "used heap";
			}
			Widgets.heading(ui, x + 12, cy + 10, w - 24, title, note);
			if (all == null) {
				ui.loading(x, cy + 26, w, chartH, "Loading samples…");
			} else {
				String kind = ping ? SampleRecord.KIND_PING : SampleRecord.KIND_MEMORY;
				List<SampleRecord> sel = all.stream().filter(r -> kind.equals(r.kind)).toList();
				if (sel.isEmpty()) {
					boolean enabled = ping ? siq.config.get().recordPing : siq.config.get().recordMemory;
					Charts.empty(ui, x + 12, cy + 26, w - 24, chartH, enabled ? "No " + (ping ? "ping" : "memory") + " samples were recorded in this session"
						: (ping ? "Ping" : "Memory") + " sampling is turned off");
				} else {
					renderSampleChart(ui, "sd#chart" + pass, x + 12, cy + 28, w - 24, chartH, sel, ping);
				}
			}
			cy += chartH + 44 + ui.gap();
		}
		return cy - y;
	}

	private void renderSampleChart(Ui ui, String id, int x, int y, int w, int h, List<SampleRecord> samples, boolean ping) {
		int buckets = Math.min(samples.size(), Math.max(10, w / 4));
		long[] values = new long[buckets];
		long[] maxes = new long[buckets];
		long[] times = new long[buckets];
		int per = (int) Math.ceil(samples.size() / (double) buckets);
		int n = 0;
		for (int i = 0; i < samples.size(); i += per) {
			double sum = 0;
			double mx = 0;
			int c = 0;
			for (int j = i; j < Math.min(samples.size(), i + per); j++) {
				sum += samples.get(j).value;
				if (samples.get(j).max != null) {
					mx = Math.max(mx, samples.get(j).max);
				}
				c++;
			}
			values[n] = Math.round(sum / c);
			maxes[n] = Math.round(mx);
			times[n] = samples.get(i).at;
			n++;
		}
		final int count = n;
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			labels.add(Fmt.time(times[i]));
		}
		long[] v = java.util.Arrays.copyOf(values, count);
		Charts.lines(ui, id, x, y, w, h, labels, new long[][]{v}, new int[]{ping ? ui.chart[0] : ui.chart[2]}, Charts.Axis.COUNT,
			i -> Fmt.dateTimeSeconds(times[i]) + "\n" + (ping ? v[i] + " ms" : v[i] + " MB used" + (maxes[i] > 0 ? " of " + maxes[i] + " MB" : "")),
			null);
	}
}
