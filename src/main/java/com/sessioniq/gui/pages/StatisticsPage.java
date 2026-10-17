package com.sessioniq.gui.pages;

import com.sessioniq.analytics.DateRange;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.analytics.StatsEngine;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Charts;
import com.sessioniq.gui.DateRangeControl;
import com.sessioniq.gui.ExportModal;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.SessionRecord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StatisticsPage extends Page {
	private static final DateRange.Preset[] PRESETS = {DateRange.Preset.TODAY, DateRange.Preset.LAST_7, DateRange.Preset.LAST_30,
		DateRange.Preset.LAST_90, DateRange.Preset.ALL, DateRange.Preset.CUSTOM};
	private static final String[] PRESET_LABELS = {"Today", "7 days", "30 days", "90 days", "All time", "Custom…"};

	private final DateRangeControl range = new DateRangeControl("stats#range", DateRange.Preset.LAST_30, List.of(PRESETS));
	private final Async<StatsEngine.Stats> stats = new Async<>();
	private int granularity;

	public StatisticsPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Statistics";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Export", Icons.EXPORT);
		StatsEngine.Stats st = stats.get();
		if (ui.button("stats#export", right - bw, y, bw, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, st != null)) {
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Statistics summary · " + st.range.describe(), "Totals, records, per-server playtime and daily playtime for the selected period.",
					List.of(Format.JSON, Format.CSV, Format.TEXT), f -> siq.exports.statistics(st, f, Fmt::duration, ServerNames::name)))));
		}
		return right - bw - 6;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int selected = -1;
		for (int i = 0; i < PRESETS.length; i++) {
			if (PRESETS[i] == range.range().preset() || PRESETS[i] == DateRange.Preset.ALL && range.range().preset() == DateRange.Preset.ALL) {
				selected = i;
			}
		}
		String[] labels = PRESET_LABELS.clone();
		if (range.range().preset() == DateRange.Preset.CUSTOM) {
			labels[5] = range.range().describe();
		}
		int segW = ui.segmentedWidth(labels);
		int choice = ui.segmented("stats#period", cx, cy, labels, selected);
		if (choice >= 0) {
			if (PRESETS[choice] == DateRange.Preset.CUSTOM) {
				range.openCustom(ui, screen);
			} else {
				range.set(DateRange.of(PRESETS[choice], Fmt.zone()));
			}
		}
		range.consumeChanged();
		if (segW + 200 < cw) {
			ui.textRight("Times in " + Fmt.zone().getId(), cx + cw, cy + 5, ui.text3);
		}
		cy += ui.buttonH() + ui.gap() + 4;

		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}
		ZoneId zone = Fmt.zone();
		DateRange r = range.range();
		boolean merge = siq.config.get().mergePorts;
		Object key = siq.dataRevision() + "|" + r + "|" + zone + "|" + merge;
		if (!key.equals(lastKey)) {
			lastKey = key;
			List<SessionRecord> snap = siq.sessions.snapshot();
			stats.reload(key, () -> siq.storage.submitQuery(() -> StatsEngine.compute(snap, r, zone, merge)));
		}
		StatsEngine.Stats st = stats.get();
		if (st == null) {
			if (stats.error() != null) {
				ui.emptyState("stats#err", cx, cy, cw, 160, Icons.WARNING, "Could not compute statistics", stats.errorMessage(), null);
			} else {
				ui.loading(cx, cy, cw, 160, "Computing statistics…");
			}
			return;
		}
		int scrollTop = cy - 4;
		int off = ui.beginScroll("stats#scroll", x, scrollTop, w, y + h - scrollTop);
		cy -= off;
		if (st.isEmpty()) {
			ui.emptyState("stats#empty", cx, cy, cw, 200, Icons.STATISTICS, "No data in this period",
				"Nothing was recorded " + (r.isAll() ? "yet" : "in " + r.describe().toLowerCase(Locale.ROOT)) + ". Choose a longer period.", null);
			ui.endScroll("stats#scroll", 220);
			return;
		}

		String[][] tiles = {
			{"Total playtime", Fmt.duration(st.playtimeMs), "active " + Fmt.duration(st.activeMs)},
			{"Multiplayer", Fmt.duration(st.multiplayerMs), Fmt.plural(st.multiplayerVisits, "server visit", "server visits")},
			{"Single-player", Fmt.duration(st.singleplayerMs), siq.config.get().trackSingleplayer ? null : "tracking off"},
			{"Application time", Fmt.duration(st.applicationMs), "idle " + Fmt.duration(st.idleMs)},
			{"Sessions", Fmt.count(st.sessions), "avg " + Fmt.duration(st.averageSessionMs)},
			{"Longest session", st.longestSession == null ? "—" : Fmt.duration(st.longestSession.runtimeMs),
				st.longestSession == null ? null : Fmt.date(st.longestSession.startedAt)},
			{"Unique servers", Fmt.count(st.uniqueServers), null},
			{"Most played server", st.mostPlayedServer == null ? "—" : ServerNames.name(st.mostPlayedServer.key),
				st.mostPlayedServer == null ? null : Fmt.duration(st.mostPlayedServer.totalMs)},
			{"Longest visit", st.longestVisit == null ? "—" : Fmt.duration(st.longestVisit.durationMs),
				st.longestVisit == null ? null : ServerNames.name(st.longestVisit.serverKey)},
			{"Chat messages", Fmt.count(st.chatMessages), null},
			{"Disconnects", Fmt.count(st.disconnects), st.unexpectedDisconnects + " unexpected"},
			{"Most recent session", st.mostRecentSession == null ? "—" : Fmt.relative(st.mostRecentSession.startedAt),
				st.mostRecentSession == null ? null : Fmt.duration(st.mostRecentSession.runtimeMs)},
		};
		int perRow = cw >= 700 ? 4 : cw >= 460 ? 3 : 2;
		int tw = (cw - ui.gap() * (perRow - 1)) / perRow;
		int th = ui.statH(true) + 16;
		for (int i = 0; i < tiles.length; i++) {
			int tx = cx + (i % perRow) * (tw + ui.gap());
			int ty = cy + (i / perRow) * (th + ui.gap());
			String tid = "stats#tile" + i;
			boolean clickable = i == 5 && st.longestSession != null || i == 7 && st.mostPlayedServer != null || i == 8 && st.longestVisit != null
				|| i == 11 && st.mostRecentSession != null;
			if (clickable) {
				ui.hit(tid, tx, ty, tw, th);
			}
			float hv = clickable ? ui.hoverAnim(tid) : 0;
			ui.panel(tx, ty, tw, th, Ui.mixArgb(ui.surface, ui.surface2, hv), hv > 0 ? Ui.mixArgb(ui.border, ui.accent, hv * 0.5f) : ui.border, ui.radius());
			ui.stat(tx + 10, ty + 8, tw - 20, tiles[i][0], tiles[i][1], tiles[i][2], i == 10 && st.unexpectedDisconnects > 0 ? ui.warning : ui.text);
			if (clickable && ui.clicked(tid)) {
				switch (i) {
					case 5 -> screen.push(new SessionDetailPage(screen, st.longestSession.id));
					case 7 -> screen.push(new ServerDetailPage(screen, st.mostPlayedServer.key));
					case 8 -> screen.push(new SessionDetailPage(screen, st.longestVisitSessionId));
					default -> screen.push(new SessionDetailPage(screen, st.mostRecentSession.id));
				}
			}
		}
		cy += ((tiles.length + perRow - 1) / perRow) * (th + ui.gap()) + 4;

		int chartH = 160;
		ui.card(cx, cy, cw, chartH + 40);
		ui.text("Playtime", cx + 12, cy + 10, ui.text);
		String[] gl = {"Daily", "Weekly", "Monthly"};
		int gw = ui.segmentedWidth(gl);
		int gsel = ui.segmented("stats#gran", cx + cw - 12 - gw, cy + 5, gl, granularity);
		if (gsel >= 0) {
			granularity = gsel;
		}
		Map<LocalDate, long[]> series = granularity == 0 ? st.daily : granularity == 1 ? st.weekly : st.monthly;
		List<String> xl = new ArrayList<>();
		List<LocalDate> keys = new ArrayList<>(series.keySet());
		long[][] values = new long[keys.size()][];
		for (int i = 0; i < keys.size(); i++) {
			LocalDate d = keys.get(i);
			xl.add(granularity == 2 ? d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + (keys.size() > 12 ? " " + (d.getYear() % 100) : "")
				: granularity == 1 ? d.getMonthValue() + "/" + d.getDayOfMonth() : keys.size() > 40 ? d.getMonthValue() + "/" + d.getDayOfMonth() : String.valueOf(d.getDayOfMonth()));
			values[i] = series.get(d);
		}
		int[] colors = {ui.chart[0], ui.chart[1]};
		int clicked = Charts.bars(ui, "stats#time", cx + 12, cy + 30, cw - 24, chartH - 8, xl, values, colors, Charts.Axis.DURATION, i -> {
			LocalDate d = keys.get(i);
			String head = granularity == 0 ? Fmt.dayLabel(d) : granularity == 1 ? "Week of " + Fmt.date(d) : d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getYear();
			return head + "\nMultiplayer " + Fmt.duration(values[i][0]) + "\nSingle-player " + Fmt.duration(values[i][1]) + (granularity == 0 ? "\nClick to open the timeline" : "");
		}, "No playtime in this period");
		if (clicked >= 0 && granularity == 0) {
			screen.push(new TimelinePage(screen, keys.get(clicked)));
		}
		Charts.legend(ui, cx + 12, cy + chartH + 24, new String[]{"Multiplayer", "Single-player"}, colors);
		cy += chartH + 40 + ui.gap();

		boolean two = cw >= 620;
		int colW = two ? (cw - ui.gap()) / 2 : cw;
		List<ServerAggregate> top = st.servers.stream().limit(10).toList();
		int rowH = ui.rowH();
		int boxH = 34 + Math.max(1, top.size()) * rowH + 10;
		ui.card(cx, cy, colW, boxH);
		Widgets.heading(ui, cx + 12, cy + 10, colW - 24, "Playtime by server", "top " + top.size());
		if (top.isEmpty()) {
			ui.text("No visits in this period.", cx + 12, cy + 34, ui.text3);
		} else {
			int c = Charts.hbars(ui, "stats#bytime", cx + 12, cy + 30, colW - 24, top.stream().map(a -> ServerNames.name(a.key)).toList(),
				top.stream().mapToLong(a -> a.totalMs).toArray(), ui.chart[0], Fmt::duration, rowH,
				i -> Fmt.plural(top.get(i).visits, "visit", "visits") + " · longest " + Fmt.duration(top.get(i).longestMs));
			if (c >= 0) {
				screen.push(new ServerDetailPage(screen, top.get(c).key));
			}
		}
		List<ServerAggregate> byVisits = new ArrayList<>(st.servers);
		byVisits.sort((a, b) -> Integer.compare(b.visits, a.visits));
		List<ServerAggregate> topVisits = byVisits.stream().limit(10).toList();
		int vx = two ? cx + colW + ui.gap() : cx;
		int vy = two ? cy : cy + boxH + ui.gap();
		int boxH2 = 34 + Math.max(1, topVisits.size()) * rowH + 10;
		ui.card(vx, vy, colW, two ? Math.max(boxH, boxH2) : boxH2);
		Widgets.heading(ui, vx + 12, vy + 10, colW - 24, "Server visit frequency", null);
		if (topVisits.isEmpty()) {
			ui.text("No visits in this period.", vx + 12, vy + 34, ui.text3);
		} else {
			int c = Charts.hbars(ui, "stats#byvisits", vx + 12, vy + 30, colW - 24, topVisits.stream().map(a -> ServerNames.name(a.key)).toList(),
				topVisits.stream().mapToLong(a -> a.visits).toArray(), ui.chart[2], v -> Fmt.count(v), rowH, null);
			if (c >= 0) {
				screen.push(new ServerDetailPage(screen, topVisits.get(c).key));
			}
		}
		cy += two ? Math.max(boxH, boxH2) + ui.gap() : boxH + boxH2 + 2 * ui.gap();

		int smallH = 140;
		int dx = cx;
		ui.card(dx, cy, colW, smallH + 34);
		Widgets.heading(ui, dx + 12, cy + 10, colW - 24, "Session duration distribution", Fmt.plural(st.sessions, "session", "sessions"));
		long[][] hist = new long[st.durationHistogram.length][];
		for (int i = 0; i < hist.length; i++) {
			hist[i] = new long[]{st.durationHistogram[i]};
		}
		Charts.bars(ui, "stats#hist", dx + 12, cy + 28, colW - 24, smallH, List.of(StatsEngine.DURATION_LABELS), hist, new int[]{ui.chart[3]}, Charts.Axis.COUNT,
			i -> StatsEngine.DURATION_LABELS[i] + ": " + Fmt.plural(st.durationHistogram[i], "session", "sessions"), "No sessions started in this period");
		int ex = two ? cx + colW + ui.gap() : cx;
		int ey = two ? cy : cy + smallH + 34 + ui.gap();
		ui.card(ex, ey, colW, smallH + 34);
		Widgets.heading(ui, ex + 12, ey + 10, colW - 24, "Disconnects over time", st.unexpectedDisconnects + " unexpected");
		Map<LocalDate, int[]> dd = st.disconnectsDaily;
		if (granularity != 0) {
			Map<LocalDate, int[]> grouped = new LinkedHashMap<>();
			for (Map.Entry<LocalDate, int[]> e : dd.entrySet()) {
				LocalDate k = granularity == 1 ? e.getKey().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) : e.getKey().withDayOfMonth(1);
				int[] t = grouped.computeIfAbsent(k, kk -> new int[2]);
				t[0] += e.getValue()[0];
				t[1] += e.getValue()[1];
			}
			dd = grouped;
		}
		List<LocalDate> dkeys = new ArrayList<>(dd.keySet());
		long[][] dv = new long[dkeys.size()][];
		List<String> dl = new ArrayList<>();
		for (int i = 0; i < dkeys.size(); i++) {
			int[] v = dd.get(dkeys.get(i));
			dv[i] = new long[]{v[0], v[1]};
			LocalDate d = dkeys.get(i);
			dl.add(granularity == 2 ? d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) : d.getMonthValue() + "/" + d.getDayOfMonth());
		}
		int[] dcColors = {ui.error, Ui.withAlpha(ui.text2, 0.7f)};
		Charts.bars(ui, "stats#dc", ex + 12, ey + 28, colW - 24, smallH - 10, dl, dv, dcColors, Charts.Axis.COUNT,
			i -> Fmt.date(dkeys.get(i)) + "\n" + dv[i][0] + " unexpected · " + dv[i][1] + " other", "No disconnects in this period");
		Charts.legend(ui, ex + 12, ey + smallH + 20, new String[]{"Unexpected", "Other"}, dcColors);
		cy += two ? smallH + 34 : 2 * (smallH + 34) + ui.gap();
		cy += ui.gap();
		ui.paragraph("Playtime is time connected to a server or inside a single-player world; it never counts the same minute twice. Application time is how long Minecraft was running; idle time is application time outside any world.",
			cx, cy, cw, ui.text3);
		cy += ui.paragraphHeight("Playtime is time connected to a server or inside a single-player world; it never counts the same minute twice. Application time is how long Minecraft was running; idle time is application time outside any world.", cw);
		ui.endScroll("stats#scroll", cy + off - scrollTop + pad);
	}

	private Object lastKey;

	@Override
	public void onShow() {
		lastKey = null;
	}
}
