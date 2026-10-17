package com.sessioniq.gui.pages;

import com.sessioniq.analytics.DateRange;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.analytics.StatsEngine;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Charts;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OverviewPage extends Page {
	private record Data(StatsEngine.Stats today, StatsEngine.Stats week, StatsEngine.Stats fortnight, StatsEngine.Stats month) {
	}

	private final Async<Data> data = new Async<>();
	private boolean recoveryDismissed;

	public OverviewPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Overview";
	}

	private void load() {
		if (!siq.isReady()) {
			return;
		}
		List<SessionRecord> snapshot = siq.sessions.snapshot();
		ZoneId zone = Fmt.zone();
		boolean merge = siq.config.get().mergePorts;
		data.ensure(siq.dataRevision() + "|" + zone + merge, () -> siq.storage.submitQuery(() -> {
			LocalDate today = LocalDate.now(zone);
			return new Data(
				StatsEngine.compute(snapshot, DateRange.of(DateRange.Preset.TODAY, zone), zone, merge),
				StatsEngine.compute(snapshot, DateRange.of(DateRange.Preset.LAST_7, zone), zone, merge),
				StatsEngine.compute(snapshot, DateRange.custom(today.minusDays(13), today), zone, merge),
				StatsEngine.compute(snapshot, DateRange.of(DateRange.Preset.LAST_30, zone), zone, merge));
		}));
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		load();
		int pad = ui.pad() + 4;
		int off = ui.beginScroll("overview", x, y, w, h);
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad - off;

		var rec = siq.recovery();
		if (rec != null && !rec.recoveredIds().isEmpty() && !recoveryDismissed) {
			int bh = 30;
			ui.panel(cx, cy, cw, bh, Ui.argb(Ui.mix(ui.th.surface, ui.th.warning, 0.12f), 1f), Ui.withAlpha(ui.warning, 0.5f), ui.radius());
			ui.icon(Icons.WARNING, cx + 10, cy + (bh - 9) / 2, ui.warning);
			int n = rec.recoveredIds().size();
			String msg = n == 1 ? "Minecraft did not close normally last time. That session was recovered." : n + " sessions were recovered after Minecraft did not close normally.";
			ui.textClip(msg, cx + 26, cy + (bh - ui.capH()) / 2 + 1, cw - 150, ui.text);
			int vx = cx + cw - 120;
			if (ui.button("ov#viewrec", vx, cy + 6, 56, bh - 12, "View", Ui.Btn.SECONDARY)) {
				screen.push(new SessionDetailPage(screen, rec.recoveredIds().getFirst()));
			}
			if (ui.button("ov#dismissrec", vx + 60, cy + 6, 56, bh - 12, "Dismiss", Ui.Btn.GHOST)) {
				recoveryDismissed = true;
			}
			cy += bh + ui.gap();
		}

		cy += renderLive(ui, cx, cy, cw) + ui.gap() + 4;

		Data d = data.get();
		if (d == null) {
			if (data.error() != null) {
				ui.emptyState("ov#err", cx, cy, cw, 140, Icons.WARNING, "Could not compute statistics", data.errorMessage(), null);
				cy += 140;
			} else {
				ui.loading(cx, cy, cw, 120, siq.isReady() ? "Computing…" : "Loading history…");
				cy += 120;
			}
			ui.endScroll("overview", cy + off - y + pad);
			return;
		}

		String[][] tiles = {
			{"Playtime today", Fmt.duration(d.today().playtimeMs), d.today().multiplayerVisits + " server visit(s)"},
			{"Last 7 days", Fmt.duration(d.week().playtimeMs), Fmt.duration(d.week().applicationMs) + " app time"},
			{"Sessions · 7 days", Fmt.count(d.week().sessions), d.week().sessions == 0 ? "none recorded" : "avg " + Fmt.duration(d.week().averageSessionMs)},
			{"Unexpected disconnects", Fmt.count(d.week().unexpectedDisconnects), "last 7 days"}
		};
		int perRow = cw >= 520 ? 4 : 2;
		int tileGap = ui.gap();
		int tileW = (cw - tileGap * (perRow - 1)) / perRow;
		int tileH = ui.statH(true) + 18;
		for (int i = 0; i < tiles.length; i++) {
			int tx = cx + (i % perRow) * (tileW + tileGap);
			int ty = cy + (i / perRow) * (tileH + tileGap);
			ui.card(tx, ty, tileW, tileH);
			int color = i == 3 && d.week().unexpectedDisconnects > 0 ? ui.warning : ui.text;
			ui.stat(tx + 10, ty + 9, tileW - 20, tiles[i][0], tiles[i][1], tiles[i][2], color);
		}
		cy += ((tiles.length + perRow - 1) / perRow) * (tileH + tileGap) + 4;

		int chartH = 150;
		ui.card(cx, cy, cw, chartH + 36);
		Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Daily playtime", "last 14 days");
		int[] colors = {ui.chart[0], ui.chart[1]};
		Charts.legend(ui, cx + 12, cy + chartH + 20, new String[]{"Multiplayer", "Single-player"}, colors);
		List<String> labels = new ArrayList<>();
		List<LocalDate> days = new ArrayList<>();
		long[][] values = new long[d.fortnight().daily.size()][];
		int i = 0;
		for (Map.Entry<LocalDate, long[]> e : d.fortnight().daily.entrySet()) {
			labels.add(e.getKey().getDayOfMonth() + "");
			days.add(e.getKey());
			values[i++] = e.getValue();
		}
		int clicked = Charts.bars(ui, "ov#daily", cx + 12, cy + 26, cw - 24, chartH - 12, labels, values, colors, Charts.Axis.DURATION,
			idx -> Fmt.dayLabel(days.get(idx)) + "\nMultiplayer " + Fmt.duration(values[idx][0]) + "\nSingle-player " + Fmt.duration(values[idx][1])
				+ "\nClick to open the timeline", "No playtime in the last 14 days");
		if (clicked >= 0) {
			screen.push(new TimelinePage(screen, days.get(clicked)));
		}
		cy += chartH + 36 + ui.gap() + 4;

		boolean twoCol = cw >= 560;
		int colW = twoCol ? (cw - ui.gap()) / 2 : cw;
		List<ServerAggregate> top = d.month().servers.stream().limit(5).toList();
		int rowH = ui.rowH();
		int serversH = 34 + Math.max(1, top.size()) * rowH + 8;
		List<SessionRecord> recent = siq.sessions.sorted().stream().limit(5).toList();
		int recentH = 34 + Math.max(1, recent.size()) * (rowH + 4) + 8;
		int boxH = twoCol ? Math.max(serversH, recentH) : serversH;
		ui.card(cx, cy, colW, boxH);
		Widgets.heading(ui, cx + 12, cy + 10, colW - 24, "Top servers", "30 days");
		if (top.isEmpty()) {
			ui.text("No server or world visits in the last 30 days.", cx + 12, cy + 34, ui.text3);
		} else {
			List<String> names = top.stream().map(a -> ServerNames.name(a.key)).toList();
			long[] vals = top.stream().mapToLong(a -> a.totalMs).toArray();
			int sel = Charts.hbars(ui, "ov#top", cx + 12, cy + 30, colW - 24, names, vals, ui.accent, Fmt::duration, rowH,
				idx -> top.get(idx).visits + " visit(s) · last played " + Fmt.relative(top.get(idx).lastPlayed));
			if (sel >= 0) {
				screen.push(new ServerDetailPage(screen, top.get(sel).key));
			}
		}
		int rx = twoCol ? cx + colW + ui.gap() : cx;
		int ry = twoCol ? cy : cy + boxH + ui.gap();
		ui.card(rx, ry, colW, twoCol ? boxH : recentH);
		Widgets.heading(ui, rx + 12, ry + 10, colW - 24, "Recent sessions", null);
		if (ui.link("ov#allsessions", "View all", rx + colW - 12 - ui.textW("View all"), ry + 10, ui.accent)) {
			screen.selectSection("sessions");
		}
		int sy = ry + 30;
		for (SessionRecord s : recent) {
			String id = "ov#s#" + s.id;
			if (Widgets.row(ui, id, rx + 4, sy, colW - 8, rowH + 2, false)) {
				screen.push(new SessionDetailPage(screen, s.id));
			}
			ui.dot(rx + 14, sy + (rowH + 2) / 2, 3, Widgets.statusColor(ui, s));
			ui.textMid(Fmt.dayLabel(Fmt.localDate(s.startedAt)) + " " + Fmt.time(s.startedAt), rx + 24, sy, rowH + 2, ui.text);
			String right = Fmt.duration(s.runtimeMs) + " · " + Fmt.sessionType(s.type);
			ui.textMid(right, rx + colW - 12 - ui.textW(right), sy, rowH + 2, ui.text2);
			sy += rowH + 4;
		}
		if (recent.isEmpty()) {
			ui.text("No sessions yet.", rx + 12, sy + 4, ui.text3);
		}
		cy += twoCol ? boxH : boxH + ui.gap() + recentH;
		ui.endScroll("overview", cy + off - y + pad);
	}

	private int renderLive(Ui ui, int x, int y, int w) {
		SessionRecord s = siq.tracker.session();
		int h = 14 + ui.lineH() + 2 + Math.round(ui.lineH() * 1.25f) + 5 + 2 * (ui.lineH() + 4) + 10;
		ui.card(x, y, w, h);
		if (s == null) {
			ui.text("CURRENT SESSION", x + 14, y + 12, ui.text3);
			ui.text("Not recording", x + 14, y + 28, ui.text);
			ui.text(siq.config.get().trackSessions ? "Starting…" : "Session tracking is turned off in Settings › Recording.", x + 14, y + 44, ui.text2);
			return h;
		}
		VisitRecord v = siq.tracker.visit();
		int colW = 96;
		boolean columns = w >= 520;
		int headW = columns ? w - colW * 3 - 40 : w - 28;
		ui.text("CURRENT SESSION", x + 14, y + 12, ui.text3);
		String headline = v == null ? siq.tracker.describeMode() : (v.isMultiplayer() ? "Connected to " : "Playing ") + ServerNames.name(v.serverKey);
		float old = ui.fontScale;
		ui.fontScale = old * 1.25f;
		ui.textClip(headline, x + 14, y + 14 + ui.lineH() + 2, headW, ui.text);
		int headH = ui.lineH();
		ui.fontScale = old;
		int subY = y + 14 + ui.lineH() + 2 + headH + 5;
		String sub = "Started " + Fmt.time(s.startedAt) + " · running " + Fmt.duration(s.runtimeMs);
		if (!columns) {
			sub += " · playtime " + Fmt.duration(s.inWorldMs);
		}
		ui.textClip(sub, x + 14, subY, headW, ui.text2);
		if (ui.link("ov#opencur", "Open session", x + 14, subY + ui.lineH() + 4, ui.accent)) {
			screen.push(new SessionDetailPage(screen, s.id));
		}
		int sx = x + w - colW * 3 - 6;
		if (columns) {
			Integer ping = siq.tracker.perf().lastPing();
			String pingText = v == null || !v.isMultiplayer() ? "—" : ping == null ? "Unavailable" : ping + " ms";
			String mem = s.memoryUsedMb == null ? "—" : Fmt.mb(s.memoryUsedMb.last) + (s.memoryMaxMb != null ? " / " + s.memoryMaxMb : "");
			String[][] cols = {
				{"Playtime", Fmt.duration(s.inWorldMs)},
				{"Ping", pingText},
				{"Memory", mem}
			};
			for (int i = 0; i < cols.length; i++) {
				int cx = sx + i * colW;
				ui.vline(cx - 8, y + 14, h - 28, ui.border);
				ui.text(cols[i][0].toUpperCase(), cx, y + 22, ui.text3);
				ui.textClip(cols[i][1], cx, y + 38, colW - 14, ui.text);
			}
		}
		return h;
	}
}
