package com.sessioniq.gui.pages;

import com.sessioniq.analytics.DateRange;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.DateRangeControl;
import com.sessioniq.gui.ExportModal;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SessionsPage extends Page {
	private static final String[] SORTS = {"Newest first", "Oldest first", "Longest first", "Shortest first"};
	private static final String[] TYPES = {"All types", "Multiplayer", "Single-player", "Mixed", "Menus only"};
	private static final String[] TYPE_KEYS = {null, SessionRecord.TYPE_MULTIPLAYER, SessionRecord.TYPE_SINGLEPLAYER, SessionRecord.TYPE_MIXED, SessionRecord.TYPE_MENU};

	private final DateRangeControl range = new DateRangeControl("sessions#range", DateRange.Preset.ALL,
		List.of(DateRange.Preset.ALL, DateRange.Preset.TODAY, DateRange.Preset.LAST_7, DateRange.Preset.LAST_30, DateRange.Preset.LAST_90, DateRange.Preset.CUSTOM));
	private int sort;
	private int type;
	private String lastQuery = "";
	private List<SessionRecord> filtered = List.of();
	private String filterKey;
	private int selectedIndex = -1;

	public SessionsPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Sessions";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Export", Icons.EXPORT);
		if (ui.button("sessions#export", right - bw, y, bw, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			List<SessionRecord> shown = new ArrayList<>(filtered);
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Sessions shown (" + shown.size() + ")", "The sessions matching the current filters, one record or row per session.",
					List.of(Format.JSON, Format.CSV), f -> siq.exports.sessions(snapshotOf(shown), f)),
				new ExportModal.Option("All sessions", "Every recorded session with visits, disconnect events and summaries.",
					List.of(Format.JSON, Format.CSV), f -> siq.exports.sessions(siq.sessions.snapshot(), f)),
				new ExportModal.Option("All server visits", "Every visit as its own record: join and leave times, durations, ping.",
					List.of(Format.CSV, Format.JSON), f -> siq.exports.visits(siq.sessions.snapshot(), f, ServerNames::name))
			)));
		}
		return right - bw - 6;
	}

	private List<SessionRecord> snapshotOf(List<SessionRecord> shown) {
		List<SessionRecord> snap = siq.sessions.snapshot();
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (SessionRecord s : shown) {
			ids.add(s.id);
		}
		snap.removeIf(s -> !ids.contains(s.id));
		return snap;
	}

	private void refilter(String query) {
		ZoneId zone = Fmt.zone();
		DateRange r = range.range();
		String key = siq.sessions.revision() + "|" + query + "|" + r + "|" + sort + "|" + type + "|" + zone;
		if (key.equals(filterKey)) {
			return;
		}
		filterKey = key;
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<SessionRecord> out = new ArrayList<>();
		for (SessionRecord s : siq.sessions.sorted()) {
			if (!r.isAll() && !r.overlaps(s.startedAt, s.endEstimate() + 1, zone)) {
				continue;
			}
			if (TYPE_KEYS[type] != null && !TYPE_KEYS[type].equals(s.type)) {
				continue;
			}
			if (!q.isEmpty() && !matches(s, q)) {
				continue;
			}
			out.add(s);
		}
		Comparator<SessionRecord> cmp = switch (sort) {
			case 1 -> Comparator.comparingLong(s -> s.startedAt);
			case 2 -> Comparator.comparingLong((SessionRecord s) -> s.runtimeMs).reversed();
			case 3 -> Comparator.comparingLong(s -> s.runtimeMs);
			default -> Comparator.comparingLong((SessionRecord s) -> s.startedAt).reversed();
		};
		out.sort(cmp);
		filtered = out;
		if (selectedIndex >= filtered.size()) {
			selectedIndex = filtered.size() - 1;
		}
	}

	private static boolean matches(SessionRecord s, String q) {
		if (s.id.toLowerCase(Locale.ROOT).contains(q) || s.mcVersion != null && s.mcVersion.toLowerCase(Locale.ROOT).contains(q)
			|| Fmt.dateTime(s.startedAt).toLowerCase(Locale.ROOT).contains(q) || Fmt.sessionType(s.type).toLowerCase(Locale.ROOT).contains(q)) {
			return true;
		}
		for (VisitRecord v : s.visits) {
			if (v.name != null && v.name.toLowerCase(Locale.ROOT).contains(q) || v.serverKey.contains(q)
				|| ServerNames.name(v.serverKey).toLowerCase(Locale.ROOT).contains(q)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int bh = ui.buttonH() + 2;

		boolean narrow = cw < 560;
		int searchW = narrow ? cw : cw - 3 * 112 - 3 * ui.gap();
		TextField search = ui.textField("sessions#search", cx, cy, searchW, bh, "Search servers, dates, versions…", Icons.SEARCH);
		int fx = narrow ? cx : cx + searchW + ui.gap();
		int fy = narrow ? cy + bh + ui.gap() : cy;
		int dw = narrow ? (cw - 2 * ui.gap()) / 3 : 112;
		range.render(ui, screen, fx, fy, dw, bh);
		int t = ui.dropdown("sessions#type", fx + dw + ui.gap(), fy, dw, bh, List.of(TYPES), type);
		if (t != type) {
			type = t;
		}
		int s = ui.dropdown("sessions#sort", fx + 2 * (dw + ui.gap()), fy, dw, bh, List.of(SORTS), sort);
		if (s != sort) {
			sort = s;
		}
		range.consumeChanged();
		search.consumeChanged();
		lastQuery = search.text;
		cy = fy + bh + ui.gap();

		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}
		refilter(lastQuery);
		ui.text(Fmt.plural(filtered.size(), "session", "sessions") + (filtered.size() != siq.sessions.size() ? " of " + Fmt.count(siq.sessions.size()) : ""),
			cx, cy + 2, ui.text3);
		cy += ui.lineH() + 6;

		int listY = cy;
		int listH = y + h - listY - pad / 2;
		ui.card(cx, listY, cw, listH);
		if (filtered.isEmpty()) {
			boolean any = siq.sessions.size() > 0;
			if (ui.emptyState("sessions#empty", cx, listY, cw, listH, Icons.SESSIONS, any ? "No matching sessions" : "No sessions recorded yet",
				any ? "Try a different search, type or date range." : "Sessions are recorded automatically while Minecraft runs.",
				any ? "Clear filters" : null)) {
				ui.field("sessions#search").set("");
				type = 0;
				range.set(DateRange.of(DateRange.Preset.ALL, Fmt.zone()));
			}
			return;
		}

		boolean wide = cw >= 640;
		int headerH = ui.rowH();
		int innerX = cx + 10;
		int innerW = cw - 20;
		int[] widths = wide ? new int[]{150, 80, 92, 60, 54, 76, 64} : new int[]{Math.max(110, innerW - 80 - 92 - 70), 80, 92};
		String[] headers = wide ? new String[]{"Started", "Duration", "Type", "Servers", "Chat", "Disconnects", "Version"} : new String[]{"Started", "Duration", "Type"};
		int[] colX = Widgets.columns(innerX, innerW, widths);
		for (int i = 0; i < headers.length; i++) {
			ui.textMid(headers[i].toUpperCase(Locale.ROOT), colX[i], listY + 2, headerH, ui.text3);
		}
		ui.textMid("STATUS", innerX + innerW - 70, listY + 2, headerH, ui.text3);
		ui.hline(cx, listY + headerH + 2, cw, ui.border);

		int rowH = ui.rowH() + 4;
		int bodyY = listY + headerH + 3;
		int bodyH = listH - headerH - 4;
		String sid = "sessions#list";
		int off = ui.beginScroll(sid, cx, bodyY, cw, bodyH);
		int first = Math.max(0, off / rowH);
		int last = Math.min(filtered.size(), (off + bodyH) / rowH + 2);
		for (int i = first; i < last; i++) {
			SessionRecord r = filtered.get(i);
			int ry = bodyY + i * rowH - off;
			if (Widgets.row(ui, "sessions#row" + r.id, cx + 1, ry, cw - 2, rowH, i == selectedIndex)) {
				selectedIndex = i;
				screen.push(new SessionDetailPage(screen, r.id));
			}
			int c = 0;
			ui.textMid(ui.ellipsize(Fmt.dateTime(r.startedAt), widths[0] - 8), colX[c++], ry, rowH, ui.text);
			ui.textMid(Fmt.duration(r.runtimeMs), colX[c++], ry, rowH, ui.text);
			ui.badge(colX[c++], ry + (rowH - ui.lineH() - 3) / 2, Fmt.sessionType(r.type), typeColor(ui, r.type));
			if (wide) {
				ui.textMid(Fmt.count(r.uniqueServers()), colX[c++], ry, rowH, ui.text2);
				ui.textMid(Fmt.count(r.chatCount), colX[c++], ry, rowH, ui.text2);
				long unexpected = r.unexpectedDisconnects();
				ui.textMid(r.disconnects.size() + (unexpected > 0 ? " (" + unexpected + "!)" : ""), colX[c++], ry, rowH, unexpected > 0 ? ui.warning : ui.text2);
				ui.textMid(ui.ellipsize(r.mcVersion == null ? "—" : r.mcVersion, widths[c] - 6), colX[c++], ry, rowH, ui.text2);
			}
			ui.dot(innerX + innerW - 70, ry + rowH / 2, 3, Widgets.statusColor(ui, r));
			ui.textMid(Widgets.statusLabel(r), innerX + innerW - 62, ry, rowH, ui.text2);
		}
		ui.endScroll(sid, filtered.size() * rowH);
	}

	static int typeColor(Ui ui, String type) {
		return switch (type == null ? "" : type) {
			case SessionRecord.TYPE_MULTIPLAYER -> ui.chart[0];
			case SessionRecord.TYPE_SINGLEPLAYER -> ui.chart[1];
			case SessionRecord.TYPE_MIXED -> ui.chart[3];
			default -> ui.text2;
		};
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (input.hasCtrlOrCmd() && input.key() == GLFW.GLFW_KEY_F) {
			ui.focus("sessions#search");
			return true;
		}
		if (filtered.isEmpty()) {
			return false;
		}
		int rowH = ui.rowH() + 4;
		switch (input.key()) {
			case GLFW.GLFW_KEY_DOWN -> {
				selectedIndex = Math.min(filtered.size() - 1, selectedIndex + 1);
				keepVisible(ui, rowH);
				return true;
			}
			case GLFW.GLFW_KEY_UP -> {
				selectedIndex = Math.max(0, selectedIndex - 1);
				keepVisible(ui, rowH);
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				ui.scrollBy("sessions#list", ui.scrollView("sessions#list") - rowH);
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				ui.scrollBy("sessions#list", -ui.scrollView("sessions#list") + rowH);
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (selectedIndex >= 0) {
					screen.push(new SessionDetailPage(screen, filtered.get(selectedIndex).id));
					return true;
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	private void keepVisible(Ui ui, int rowH) {
		int off = ui.scrollOffset("sessions#list");
		int view = ui.scrollView("sessions#list");
		int top = selectedIndex * rowH;
		if (top < off) {
			ui.scrollTo("sessions#list", top);
		} else if (top + rowH > off + view) {
			ui.scrollTo("sessions#list", top + rowH - view);
		}
	}
}
