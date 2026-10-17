package com.sessioniq.gui.pages;

import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.gui.Charts;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.PromptModal;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MetricSummary;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.DataActions;
import com.sessioniq.track.ServerIdentity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServerDetailPage extends Page {
	private final String key;
	private boolean newestFirst = true;
	private int cachedRevision = -1;
	private final List<VisitRef> visits = new ArrayList<>();
	private final List<DisconnectRecord> disconnects = new ArrayList<>();
	private ServerAggregate agg;
	private MetricSummary ping;

	private record VisitRef(SessionRecord session, VisitRecord visit) {
	}

	public ServerDetailPage(SiqScreen screen, String key) {
		super(screen);
		this.key = key;
	}

	@Override
	public String title() {
		return ServerNames.name(key);
	}

	private void refresh() {
		int rev = siq.sessions.revision();
		if (rev == cachedRevision) {
			return;
		}
		cachedRevision = rev;
		visits.clear();
		disconnects.clear();
		boolean merge = siq.config.get().mergePorts;
		ping = null;
		for (SessionRecord s : siq.sessions.sorted()) {
			for (VisitRecord v : s.visits) {
				if (key.equals(ServerIdentity.groupKey(v, merge))) {
					visits.add(new VisitRef(s, v));
					if (v.ping != null && v.ping.count > 0) {
						if (ping == null) {
							ping = new MetricSummary();
						}
						ping.merge(v.ping);
					}
				}
			}
			for (DisconnectRecord d : s.disconnects) {
				if (d.serverKey != null && key.equals(ServerIdentity.groupKey(d.serverKey, merge))) {
					disconnects.add(d);
				}
			}
		}
		List<ServerAggregate> all = ServerAggregate.compute(siq.sessions.sorted(), merge, null, Fmt.zone());
		agg = all.stream().filter(a -> a.key.equals(key)).findFirst().orElse(null);
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int x = right;
		int dw = ui.buttonWidth("Delete history", Icons.TRASH);
		x -= dw;
		if (ui.button("srv#delete", x, y, dw, h, "Delete history", Icons.TRASH, Ui.Btn.DANGER, !visits.isEmpty() || !disconnects.isEmpty())) {
			boolean live = siq.tracker.visit() != null && key.equals(ServerIdentity.groupKey(siq.tracker.visit(), siq.config.get().mergePorts));
			int chat = visits.stream().mapToInt(v -> v.visit().chatCount).sum();
			screen.confirm("Delete history of " + ServerNames.name(key) + "?",
				"This permanently deletes " + Fmt.plural(visits.size() - (live ? 1 : 0), "recorded visit", "recorded visits") + ", "
					+ Fmt.plural(disconnects.size(), "disconnect event", "disconnect events") + " and about " + Fmt.plural(chat, "chat message", "chat messages")
					+ " from this server, plus its nickname and group. Sessions remain, without these visits. "
					+ (live ? "The visit in progress is kept. " : "") + "Only the nickname can be removed separately with Rename.",
				"Delete history", true, () -> DataActions.deleteServerHistory(siq, key).whenCompleteAsync((msg, err) -> {
					if (err != null) {
						screen.toast("Deletion finished with an error: " + err.getMessage(), SiqScreen.TOAST_ERROR);
					} else {
						screen.toast(msg, SiqScreen.TOAST_SUCCESS);
					}
					ServerNames.invalidate();
				}, com.sessioniq.SessionIQ.mainThread()));
		}
		x -= 6;
		int gw = ui.buttonWidth("Group", null);
		x -= gw;
		if (ui.button("srv#group", x, y, gw, h, "Group", Ui.Btn.SECONDARY)) {
			String current = siq.servers.group(key);
			String existing = String.join(", ", siq.servers.groups());
			screen.openModal(new PromptModal("Server group", existing.isEmpty() ? "Group name (leave empty for none)" : "Group name (existing: " + existing + ")",
				current, "Save", 32, null, v -> siq.servers.setGroup(key, v), ui));
		}
		x -= 6;
		int rw = ui.buttonWidth("Rename", Icons.EDIT);
		x -= rw;
		if (ui.button("srv#rename", x, y, rw, h, "Rename", Icons.EDIT, Ui.Btn.SECONDARY, true)) {
			screen.openModal(new PromptModal("Nickname", "Shown instead of the recorded name. Leave empty to remove it; the recorded name is kept.",
				siq.servers.nickname(key), "Save", 48, null, v -> siq.servers.setNickname(key, v), ui));
		}
		return x - 6;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		refresh();
		int pad = ui.pad() + 4;
		int off = ui.beginScroll("srv#scroll", x, y, w, h);
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad - off;

		if (agg == null && visits.isEmpty()) {
			boolean back = ui.emptyState("srv#none", cx, cy, cw, 200, Icons.SERVERS, "No history for this server", "Its history may have been deleted.", "Back");
			ui.endScroll("srv#scroll", 220);
			if (back) {
				screen.back();
			}
			return;
		}
		boolean world = ServerIdentity.isWorld(key);
		int idH = 58;
		ui.card(cx, cy, cw, idH);
		ui.icon(world ? Icons.GLOBE : Icons.SERVERS, cx + 12, cy + 12, ui.accent);
		float old = ui.fontScale;
		ui.fontScale = old * 1.3f;
		ui.textClip(ServerNames.name(key), cx + 28, cy + 10, cw - 200, ui.text);
		ui.fontScale = old;
		String nick = siq.servers.nickname(key);
		String grp = siq.servers.group(key);
		String sub = (world ? "Single-player world" : ServerNames.address(key)) + (nick != null ? " · recorded as " + ServerNames.recordedName(key) : "");
		ui.textClip(sub, cx + 28, cy + 30, cw - 200, ui.text2);
		if (agg != null) {
			ui.textClip("First seen " + Fmt.date(agg.firstSeen) + (agg.lastPlayed > 0 ? " · last played " + Fmt.relative(agg.lastPlayed) : ""), cx + 28, cy + 42, cw - 200, ui.text3);
		}
		if (grp != null) {
			ui.badge(cx + cw - 12 - ui.badgeW(grp), cy + 12, grp, ui.accent);
		}
		cy += idH + ui.gap();

		long total = visits.stream().mapToLong(v -> v.visit().durationMs).sum();
		long longest = visits.stream().mapToLong(v -> v.visit().durationMs).max().orElse(0);
		int chat = visits.stream().mapToInt(v -> v.visit().chatCount).sum();
		long unexpected = disconnects.stream().filter(DisconnectRecord::isUnexpected).count();
		String[][] tiles = {
			{"Total playtime", Fmt.duration(total), null},
			{"Visits", Fmt.count(visits.size()), visits.isEmpty() ? null : "avg " + Fmt.duration(total / Math.max(1, visits.size()))},
			{"Longest visit", Fmt.duration(longest), null},
			{"Disconnects", Fmt.count(disconnects.size()), unexpected + " unexpected"},
			{"Chat messages", Fmt.count(chat), null},
			{"Average ping", world ? "—" : ping == null ? "Unavailable" : Fmt.ms(ping.avg()), ping == null ? null : "min " + Fmt.ms(ping.min) + " · max " + Fmt.ms(ping.max)},
		};
		int perRow = cw >= 600 ? 6 : cw >= 400 ? 3 : 2;
		int tw = (cw - ui.gap() * (perRow - 1)) / perRow;
		int th = ui.statH(true) + 16;
		for (int i = 0; i < tiles.length; i++) {
			int tx = cx + (i % perRow) * (tw + ui.gap());
			int ty = cy + (i / perRow) * (th + ui.gap());
			ui.card(tx, ty, tw, th);
			ui.stat(tx + 10, ty + 8, tw - 20, tiles[i][0], tiles[i][1], tiles[i][2], i == 3 && unexpected > 0 ? ui.warning : ui.text);
		}
		cy += ((tiles.length + perRow - 1) / perRow) * (th + ui.gap());

		ZoneId zone = Fmt.zone();
		LocalDate today = LocalDate.now(zone);
		Map<LocalDate, long[]> days = new LinkedHashMap<>();
		for (int i = 29; i >= 0; i--) {
			days.put(today.minusDays(i), new long[1]);
		}
		for (VisitRef v : visits) {
			long start = v.visit().joinedAt;
			long end = start + v.visit().durationMs;
			long cursor = start;
			while (cursor < end) {
				LocalDate d = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
				long next = Math.min(end, d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
				long[] b = days.get(d);
				if (b != null) {
					b[0] += next - cursor;
				}
				cursor = next;
			}
		}
		List<String> labels = new ArrayList<>();
		List<LocalDate> dayList = new ArrayList<>(days.keySet());
		long[][] vals = new long[days.size()][];
		int idx = 0;
		for (Map.Entry<LocalDate, long[]> e : days.entrySet()) {
			labels.add(String.valueOf(e.getKey().getDayOfMonth()));
			vals[idx++] = e.getValue();
		}
		int chartH = 120;
		ui.card(cx, cy, cw, chartH + 30);
		Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Playtime per day", "last 30 days");
		int clicked = Charts.bars(ui, "srv#chart", cx + 12, cy + 26, cw - 24, chartH - 2, labels, vals, new int[]{ui.accent}, Charts.Axis.DURATION,
			i -> Fmt.dayLabel(dayList.get(i)) + "\n" + Fmt.duration(vals[i][0]), "No playtime on this server in the last 30 days");
		if (clicked >= 0) {
			screen.push(new TimelinePage(screen, dayList.get(clicked)));
		}
		cy += chartH + 30 + ui.gap();

		int lx = cx;
		if (ui.link("srv#chat", "View chat from this server", lx, cy, ui.accent)) {
			ChatPage page = new ChatPage(screen);
			page.presetServer(key);
			screen.push(page);
		}
		lx += ui.textW("View chat from this server") + 16;
		if (!world && ui.link("srv#dcs", "View disconnects", lx, cy, ui.accent)) {
			DisconnectsPage page = new DisconnectsPage(screen);
			page.presetServer(key);
			screen.push(page);
		}
		cy += ui.lineH() + 10;

		if (agg != null && agg.names.size() > 1) {
			ui.text("Recorded names: " + String.join(", ", agg.names), cx, cy, ui.text3);
			cy += ui.lineH() + 8;
		}

		int rh = ui.lineH() * 2 + 14;
		int headerH = 30;
		ui.card(cx, cy, cw, headerH + Math.max(1, visits.size()) * rh + 6);
		Widgets.heading(ui, cx + 12, cy + 10, cw - 140, "Visits", null);
		String order = newestFirst ? "Newest first" : "Oldest first";
		if (ui.link("srv#order", order, cx + cw - 12 - ui.textW(order), cy + 10, ui.accent)) {
			newestFirst = !newestFirst;
		}
		int ry = cy + headerH;
		List<VisitRef> ordered = new ArrayList<>(visits);
		ordered.sort((a, b) -> newestFirst ? Long.compare(b.visit().joinedAt, a.visit().joinedAt) : Long.compare(a.visit().joinedAt, b.visit().joinedAt));
		if (ordered.isEmpty()) {
			ui.text("Only failed connection attempts were recorded for this server.", cx + 12, ry + 4, ui.text3);
		}
		long cumulative = 0;
		List<Long> cumulativeTotals = new ArrayList<>();
		List<VisitRef> chrono = new ArrayList<>(visits);
		chrono.sort((a, b) -> Long.compare(a.visit().joinedAt, b.visit().joinedAt));
		Map<VisitRecord, Long> cumulativeBy = new java.util.IdentityHashMap<>();
		for (VisitRef v : chrono) {
			cumulative += v.visit().durationMs;
			cumulativeBy.put(v.visit(), cumulative);
		}
		for (VisitRef ref : ordered) {
			VisitRecord v = ref.visit();
			if (ui.visible(ry, rh)) {
				if (Widgets.row(ui, "srv#v" + v.id, cx + 1, ry, cw - 2, rh, false)) {
					screen.push(new SessionDetailPage(screen, ref.session().id));
				}
				ui.dot(cx + 14, ry + 9, 3, v.open ? ui.success : v.interrupted ? ui.warning : ui.text3);
				ui.text(Fmt.dateTime(v.joinedAt) + " – " + (v.open ? "now" : v.leftAt == null ? "?" : Fmt.time(v.leftAt)), cx + 24, ry + 5, ui.text);
				String detail = SessionDetailPage.endLabel(ref.session(), v) + " · " + v.chatCount + " chat"
					+ (v.ping != null && v.ping.count > 0 ? " · ping " + Fmt.ms(v.ping.avg()) : "") + (!v.name.equals(ServerNames.recordedName(key)) ? " · as \"" + v.name + "\"" : "");
				ui.textClip(detail, cx + 24, ry + 9 + ui.lineH(), cw - 180, ui.text3);
				ui.textRight(Fmt.duration(v.durationMs), cx + cw - 12, ry + 5, ui.text);
				ui.textRight("total " + Fmt.duration(cumulativeBy.getOrDefault(v, 0L)), cx + cw - 12, ry + 9 + ui.lineH(), ui.text3);
			}
			ry += rh;
		}
		cy = ry + 10;
		ui.endScroll("srv#scroll", cy + off - y + pad);
	}
}
