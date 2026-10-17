package com.sessioniq.gui.pages;

import com.sessioniq.analytics.DateRange;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.Async;
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
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ServersPage extends Page {
	private static final String[] SORTS = {"Most playtime", "Most visits", "Recently played", "Name (A–Z)"};
	private static final String[] KINDS = {"Servers", "Worlds", "Servers and worlds"};

	private final DateRangeControl range = new DateRangeControl("servers#range", DateRange.Preset.ALL,
		List.of(DateRange.Preset.ALL, DateRange.Preset.TODAY, DateRange.Preset.LAST_7, DateRange.Preset.LAST_30, DateRange.Preset.LAST_90, DateRange.Preset.CUSTOM));
	private final Async<List<ServerAggregate>> data = new Async<>();
	private int sort;
	private int kind;
	private int group;
	private int selectedIndex = -1;
	private List<ServerAggregate> shown = List.of();

	public ServersPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Servers";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Export", Icons.EXPORT);
		if (ui.button("servers#export", right - bw, y, bw, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Server history", "Totals per server with nickname, group and every visit (JSON) or one row per server (CSV).",
					List.of(Format.JSON, Format.CSV), f -> siq.exports.servers(siq.sessions.snapshot(), f, ServerNames::name, siq.servers::group)),
				new ExportModal.Option("All server visits", "One record per visit with join and leave times.",
					List.of(Format.CSV, Format.JSON), f -> siq.exports.visits(siq.sessions.snapshot(), f, ServerNames::name))
			)));
		}
		return right - bw - 6;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int bh = ui.buttonH() + 2;
		boolean narrow = cw < 620;
		int dw = narrow ? (cw - 3 * ui.gap()) / 4 : 104;
		int searchW = narrow ? cw : cw - 4 * (dw + ui.gap());
		TextField search = ui.textField("servers#search", cx, cy, searchW, bh, "Search name, address, nickname or group…", Icons.SEARCH);
		search.consumeChanged();
		int fx = narrow ? cx : cx + searchW + ui.gap();
		int fy = narrow ? cy + bh + ui.gap() : cy;
		range.render(ui, screen, fx, fy, dw, bh);
		range.consumeChanged();
		int k = ui.dropdown("servers#kind", fx + (dw + ui.gap()), fy, dw, bh, List.of(KINDS), kind);
		if (k != kind) {
			kind = k;
		}
		List<String> groups = new ArrayList<>();
		groups.add("All groups");
		groups.addAll(siq.servers.groups());
		group = Math.min(group, groups.size() - 1);
		int g = ui.dropdown("servers#group", fx + 2 * (dw + ui.gap()), fy, dw, bh, groups, group);
		if (g != group) {
			group = g;
		}
		int s = ui.dropdown("servers#sort", fx + 3 * (dw + ui.gap()), fy, dw, bh, List.of(SORTS), sort);
		if (s != sort) {
			sort = s;
		}
		cy = fy + bh + ui.gap();

		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}
		ZoneId zone = Fmt.zone();
		DateRange r = range.range();
		boolean merge = siq.config.get().mergePorts;
		Object key = siq.dataRevision() + "|" + r + "|" + merge + "|" + zone;
		if (!key.equals(lastKey)) {
			lastKey = key;
			List<SessionRecord> snap = siq.sessions.snapshot();
			data.reload(key, () -> siq.storage.submitQuery(() -> ServerAggregate.compute(snap, merge, r, zone)));
		}
		List<ServerAggregate> all = data.get();
		if (all == null) {
			ui.loading(cx, cy, cw, h - (cy - y), "Computing…");
			return;
		}
		String q = search.text.trim().toLowerCase(Locale.ROOT);
		String groupName = group == 0 ? null : groups.get(group);
		List<ServerAggregate> list = new ArrayList<>();
		for (ServerAggregate a : all) {
			if (kind == 0 && a.isWorld() || kind == 1 && !a.isWorld()) {
				continue;
			}
			if (a.visits == 0 && !r.isAll()) {
				continue;
			}
			if (groupName != null && !groupName.equalsIgnoreCase(String.valueOf(siq.servers.group(a.key)))) {
				continue;
			}
			if (!q.isEmpty()) {
				String hay = (ServerNames.name(a.key) + " " + a.name + " " + (a.address == null ? "" : a.address) + " " + a.key + " "
					+ String.join(" ", a.names) + " " + (siq.servers.group(a.key) == null ? "" : siq.servers.group(a.key))).toLowerCase(Locale.ROOT);
				if (!hay.contains(q)) {
					continue;
				}
			}
			list.add(a);
		}
		Comparator<ServerAggregate> cmp = switch (sort) {
			case 1 -> Comparator.comparingInt((ServerAggregate a) -> a.visits).reversed();
			case 2 -> Comparator.comparingLong((ServerAggregate a) -> a.lastPlayed).reversed();
			case 3 -> Comparator.comparing((ServerAggregate a) -> ServerNames.name(a.key).toLowerCase(Locale.ROOT));
			default -> Comparator.comparingLong((ServerAggregate a) -> a.totalMs).reversed();
		};
		list.sort(cmp);
		shown = list;
		ui.text(Fmt.plural(list.size(), kind == 1 ? "world" : "server", kind == 1 ? "worlds" : "servers") + (r.isAll() ? "" : " · " + r.describe()), cx, cy + 2, ui.text3);
		cy += ui.lineH() + 6;

		int listY = cy;
		int listH = y + h - listY - pad / 2;
		ui.card(cx, listY, cw, listH);
		if (list.isEmpty()) {
			boolean any = !all.isEmpty();
			if (ui.emptyState("servers#empty", cx, listY, cw, listH, Icons.SERVERS, any ? "No matching servers" : "No servers recorded yet",
				any ? "Try a different search, group, type or date range." : "Join a multiplayer server and it will appear here with its playtime and visits.",
				any ? "Clear filters" : null)) {
				ui.field("servers#search").set("");
				group = 0;
				kind = 2;
				range.set(DateRange.of(DateRange.Preset.ALL, zone));
			}
			return;
		}
		boolean wide = cw >= 640;
		int innerX = cx + 10;
		int innerW = cw - 20;
		String[] cols = wide ? new String[]{"Playtime", "Visits", "Longest", "Last played", "Disconnects"} : new String[]{"Playtime", "Visits"};
		int[] colW = wide ? new int[]{76, 50, 70, 92, 76} : new int[]{76, 46};
		int colsTotal = 0;
		for (int c : colW) {
			colsTotal += c;
		}
		int nameW = innerW - colsTotal;
		int headerH = ui.rowH();
		ui.textMid("SERVER", innerX, listY + 2, headerH, ui.text3);
		int hx = innerX + nameW;
		for (int i = 0; i < cols.length; i++) {
			ui.textMid(cols[i].toUpperCase(Locale.ROOT), hx, listY + 2, headerH, ui.text3);
			hx += colW[i];
		}
		ui.hline(cx, listY + headerH + 2, cw, ui.border);
		int rowH = ui.lineH() * 2 + 14;
		int bodyY = listY + headerH + 3;
		int bodyH = listH - headerH - 4;
		String sid = "servers#list";
		int off = ui.beginScroll(sid, cx, bodyY, cw, bodyH);
		int first = Math.max(0, off / rowH);
		int last = Math.min(list.size(), (off + bodyH) / rowH + 2);
		for (int i = first; i < last; i++) {
			ServerAggregate a = list.get(i);
			int ry = bodyY + i * rowH - off;
			if (Widgets.row(ui, "servers#row" + a.key, cx + 1, ry, cw - 2, rowH, i == selectedIndex)) {
				selectedIndex = i;
				screen.push(new ServerDetailPage(screen, a.key));
			}
			ui.icon(a.isWorld() ? Icons.GLOBE : Icons.SERVERS, innerX, ry + 6, ui.text2);
			String name = ServerNames.name(a.key);
			String grp = siq.servers.group(a.key);
			int nx = innerX + 14;
			int nameMax = nameW - 24 - (grp != null ? ui.badgeW(grp) + 6 : 0);
			ui.textClip(name, nx, ry + 6, nameMax, ui.text);
			if (grp != null) {
				ui.badge(nx + Math.min(nameMax, ui.textW(name)) + 6, ry + 4, grp, ui.accent);
			}
			String secondary = a.isWorld() ? "Single-player world" : ServerNames.address(a.key);
			if (siq.servers.nickname(a.key) != null) {
				secondary = a.name + " · " + secondary;
			}
			ui.textClip(secondary, nx, ry + 8 + ui.lineH(), nameW - 24, ui.text3);
			int vx = innerX + nameW;
			int midY = ry + (rowH - ui.capH()) / 2;
			ui.text(Fmt.duration(a.totalMs), vx, midY, ui.text);
			vx += colW[0];
			ui.text(Fmt.count(a.visits), vx, midY, ui.text2);
			vx += colW[1];
			if (wide) {
				ui.text(Fmt.duration(a.longestMs), vx, midY, ui.text2);
				vx += colW[2];
				ui.text(a.lastPlayed > 0 ? Fmt.relative(a.lastPlayed) : "never joined", vx, midY, ui.text2);
				vx += colW[3];
				ui.text(a.disconnects + (a.unexpectedDisconnects > 0 ? " (" + a.unexpectedDisconnects + "!)" : ""), vx, midY,
					a.unexpectedDisconnects > 0 ? ui.warning : ui.text2);
			}
		}
		ui.endScroll(sid, list.size() * rowH);
	}

	private Object lastKey;

	@Override
	public void onShow() {
		lastKey = null;
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (input.hasCtrlOrCmd() && input.key() == GLFW.GLFW_KEY_F) {
			ui.focus("servers#search");
			return true;
		}
		if (shown.isEmpty()) {
			return false;
		}
		int rowH = ui.lineH() * 2 + 14;
		switch (input.key()) {
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
				selectedIndex = Math.max(0, Math.min(shown.size() - 1, selectedIndex + (input.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
				int off = ui.scrollOffset("servers#list");
				int view = ui.scrollView("servers#list");
				if (selectedIndex * rowH < off) {
					ui.scrollTo("servers#list", selectedIndex * rowH);
				} else if ((selectedIndex + 1) * rowH > off + view) {
					ui.scrollTo("servers#list", (selectedIndex + 1) * rowH - view);
				}
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (selectedIndex >= 0) {
					screen.push(new ServerDetailPage(screen, shown.get(selectedIndex).key));
					return true;
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}
}
