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
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.track.ServerIdentity;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DisconnectsPage extends Page {
	private final DateRangeControl range = new DateRangeControl("dc#range", DateRange.Preset.ALL,
		List.of(DateRange.Preset.ALL, DateRange.Preset.TODAY, DateRange.Preset.LAST_7, DateRange.Preset.LAST_30, DateRange.Preset.LAST_90, DateRange.Preset.CUSTOM));
	private boolean unexpectedOnly;
	private int category;
	private String serverKey;
	private String filterKey;
	private final List<Row> rows = new ArrayList<>();
	private int selectedIndex = -1;

	private record Row(SessionRecord session, DisconnectRecord record) {
	}

	public DisconnectsPage(SiqScreen screen) {
		super(screen);
	}

	public void presetServer(String key) {
		serverKey = key;
	}

	@Override
	public String title() {
		return serverKey == null ? "Disconnects" : "Disconnects · " + ServerNames.name(serverKey);
	}

	@Override
	public String crumb() {
		return "Disconnects";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Export", Icons.EXPORT);
		if (ui.button("dc#export", right - bw, y, bw, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Disconnect history", "Every disconnect event and failed connection with reasons, evidence and reconnection outcomes.",
					List.of(Format.JSON, Format.CSV), f -> siq.exports.disconnects(siq.sessions.snapshot(), f, ServerNames::name)))));
		}
		return right - bw - 6;
	}

	private void refilter(String query) {
		ZoneId zone = Fmt.zone();
		DateRange r = range.range();
		boolean merge = siq.config.get().mergePorts;
		String key = siq.sessions.revision() + "|" + query + "|" + r + "|" + unexpectedOnly + "|" + category + "|" + serverKey + "|" + merge;
		if (key.equals(filterKey)) {
			return;
		}
		filterKey = key;
		rows.clear();
		String q = query.trim().toLowerCase(Locale.ROOT);
		DisconnectCategory cat = category == 0 ? null : DisconnectCategory.values()[category - 1];
		for (SessionRecord s : siq.sessions.sorted()) {
			for (DisconnectRecord d : s.disconnects) {
				if (unexpectedOnly && !d.isUnexpected()) {
					continue;
				}
				if (cat != null && d.categoryEnum() != cat) {
					continue;
				}
				if (!r.isAll() && !r.contains(d.at, zone)) {
					continue;
				}
				if (serverKey != null && (d.serverKey == null || !serverKey.equals(ServerIdentity.groupKey(d.serverKey, merge)))) {
					continue;
				}
				if (!q.isEmpty()) {
					String hay = (ServerNames.name(d.serverKey) + " " + d.serverName + " " + d.reason + " " + d.details + " " + d.categoryEnum().label + " "
						+ (d.address == null ? "" : d.address)).toLowerCase(Locale.ROOT);
					if (!hay.contains(q)) {
						continue;
					}
				}
				rows.add(new Row(s, d));
			}
		}
		rows.sort((a, b) -> Long.compare(b.record().at, a.record().at));
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int bh = ui.buttonH() + 2;
		boolean narrow = cw < 600;
		int dw = narrow ? (cw - 2 * ui.gap()) / 3 : 120;
		int searchW = narrow ? cw : cw - 3 * (dw + ui.gap());
		TextField search = ui.textField("dc#search", cx, cy, searchW, bh, "Search server or reason…", Icons.SEARCH);
		search.consumeChanged();
		int fx = narrow ? cx : cx + searchW + ui.gap();
		int fy = narrow ? cy + bh + ui.gap() : cy;
		List<String> cats = new ArrayList<>();
		cats.add("All categories");
		for (DisconnectCategory c : DisconnectCategory.values()) {
			cats.add(c.label);
		}
		int c = ui.dropdown("dc#cat", fx, fy, dw, bh, cats, category);
		if (c != category) {
			category = c;
		}
		range.render(ui, screen, fx + dw + ui.gap(), fy, dw, bh);
		range.consumeChanged();
		int tx = fx + 2 * (dw + ui.gap());
		if (ui.toggle("dc#unexpected", tx, fy + (bh - 13) / 2, unexpectedOnly)) {
			unexpectedOnly = !unexpectedOnly;
		}
		ui.textMid("Unexpected only", tx + 30, fy, bh, ui.text2);
		cy = fy + bh + ui.gap();
		if (serverKey != null) {
			String label = "Server: " + ServerNames.name(serverKey);
			int lw = ui.textW(label) + 30;
			int chipH = ui.lineH() + 7;
			ui.roundRect(cx, cy, lw, chipH, Math.min(ui.th.radius, chipH / 2), ui.selected);
			ui.text(label, cx + 8, cy + 4, ui.text);
			if (ui.iconButton("dc#clearserver", cx + lw - 16, cy + (chipH - 13) / 2, 13, Icons.CLOSE, "Remove server filter")) {
				serverKey = null;
			}
			cy += chipH + ui.gap();
		}
		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}
		refilter(search.text);

		Map<DisconnectCategory, Integer> counts = new EnumMap<>(DisconnectCategory.class);
		int unexpected = 0;
		for (Row r : rows) {
			counts.merge(r.record().categoryEnum(), 1, Integer::sum);
			if (r.record().isUnexpected()) {
				unexpected++;
			}
		}
		String common = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey().label).orElse("—");
		ui.text(Fmt.plural(rows.size(), "event", "events") + " · " + unexpected + " unexpected · most common: " + common, cx, cy + 2, ui.text3);
		cy += ui.lineH() + 8;

		int listY = cy;
		int listH = y + h - listY - pad / 2;
		ui.card(cx, listY, cw, listH);
		if (rows.isEmpty()) {
			boolean any = siq.sessions.sorted().stream().anyMatch(s -> !s.disconnects.isEmpty());
			if (ui.emptyState("dc#empty", cx, listY, cw, listH, Icons.DISCONNECTS, any ? "No matching events" : "No disconnects recorded",
				any ? "Nothing matches these filters." : "When a connection to a server ends or fails, it is recorded here with whatever the client could observe about why.",
				any ? "Clear filters" : null)) {
				ui.field("dc#search").set("");
				category = 0;
				unexpectedOnly = false;
				serverKey = null;
				range.set(DateRange.of(DateRange.Preset.ALL, Fmt.zone()));
			}
			return;
		}
		int rowH = ui.rowH() * 2 + 6;
		String sid = "dc#list";
		int off = ui.beginScroll(sid, cx, listY + 1, cw, listH - 2);
		int first = Math.max(0, off / rowH);
		int last = Math.min(rows.size(), (off + listH) / rowH + 2);
		for (int i = first; i < last; i++) {
			Row r = rows.get(i);
			int ry = listY + 1 + i * rowH - off;
			if (Widgets.row(ui, "dc#row" + r.record().id, cx + 1, ry, cw - 2, rowH, i == selectedIndex)) {
				selectedIndex = i;
				screen.push(new DisconnectDetailPage(screen, r.session().id, r.record().id));
			}
			SessionDetailPage.renderDisconnectRow(ui, r.record(), cx + 10, ry, cw - 20, rowH);
		}
		ui.endScroll(sid, rows.size() * rowH);
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (input.hasCtrlOrCmd() && input.key() == GLFW.GLFW_KEY_F) {
			ui.focus("dc#search");
			return true;
		}
		if (rows.isEmpty()) {
			return false;
		}
		int rowH = ui.rowH() * 2 + 6;
		if (input.key() == GLFW.GLFW_KEY_DOWN || input.key() == GLFW.GLFW_KEY_UP) {
			selectedIndex = Math.max(0, Math.min(rows.size() - 1, selectedIndex + (input.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
			int off = ui.scrollOffset("dc#list");
			int view = ui.scrollView("dc#list");
			if (selectedIndex * rowH < off) {
				ui.scrollTo("dc#list", selectedIndex * rowH);
			} else if ((selectedIndex + 1) * rowH > off + view) {
				ui.scrollTo("dc#list", (selectedIndex + 1) * rowH - view);
			}
			return true;
		}
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && selectedIndex >= 0) {
			Row r = rows.get(selectedIndex);
			screen.push(new DisconnectDetailPage(screen, r.session().id, r.record().id));
			return true;
		}
		return false;
	}
}
