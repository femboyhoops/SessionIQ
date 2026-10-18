package com.sessioniq.gui.pages;

import com.google.gson.JsonObject;
import com.sessioniq.SessionIQ;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Charts;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.OnlineGate;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.online.Api;
import com.sessioniq.online.ApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owner-only page: anonymous usage numbers and the suggestions inbox. The server checks the account. */
public class AdminPage extends Page {
	private static final String[] FILTERS = {"Open", "Done", "All"};
	private static final String[] FILTER_KEYS = {"open", "done", "all"};
	private final Async<Api.AdminOverview> overview = new Async<>();
	private final Async<Api.Suggestions> inbox = new Async<>();
	private int filter;
	private long loadedAt;

	public AdminPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Admin";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Refresh", Icons.REFRESH);
		if (ui.button("admin#refresh", right - bw, y, bw, h, "Refresh", Icons.REFRESH, Ui.Btn.SECONDARY, siq.online.isAdmin())) {
			reload();
		}
		return right - bw - 6;
	}

	private void reload() {
		loadedAt = System.currentTimeMillis();
		overview.reload(loadedAt, () -> siq.online.get("/v1/admin/overview", Api.AdminOverview.class));
		reloadInbox();
	}

	private void reloadInbox() {
		String key = FILTER_KEYS[filter];
		inbox.reload(key + System.nanoTime(), () -> siq.online.get("/v1/admin/suggestions?status=" + key, Api.Suggestions.class));
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		if (OnlineGate.blocked(ui, screen, "admin#gate", cx, y + pad, cw, h - pad * 2, true)) {
			return;
		}
		if (!siq.online.isAdmin()) {
			ui.emptyState("admin#no", cx, y + pad, cw, h - pad * 2, Icons.SHIELD, "Admin only", "This page is only available to the SessionIQ developer's account.", null);
			return;
		}
		if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > 30_000 && !overview.loading()) {
			reload();
		}
		int off = ui.beginScroll("admin#scroll", x, y, w, h);
		int cy = y + pad - off;
		Api.AdminOverview o = overview.get();
		if (o == null) {
			if (overview.error() != null) {
				ui.text("Could not load usage: " + overview.errorMessage(), cx, cy, ui.error);
			} else {
				ui.loading(cx, cy, cw, 100, "Loading usage…");
			}
			cy += 110;
		} else {
			cy += renderOnlineNow(ui, o, cx, cy, cw) + ui.gap();
			String[][] tiles = {
				{"Total launches", Fmt.count(o.launches), "with SessionIQ"},
				{"Users", Fmt.count(o.installs), "installs seen"},
				{"Active today", Fmt.count(o.active1d), "last 24 hours"},
				{"Active this week", Fmt.count(o.active7d), Fmt.count(o.active30d) + " in 30 days"},
				{"Signed-in players", Fmt.count(o.players), Fmt.count(o.playersActive7d) + " active this week"},
				{"New suggestions", Fmt.count(o.newSuggestions), "unread"},
			};
			int perRow = cw >= 700 ? 3 : 2;
			int tw = (cw - ui.gap() * (perRow - 1)) / perRow;
			int th = ui.statH(true) + 16;
			for (int i = 0; i < tiles.length; i++) {
				int tx = cx + (i % perRow) * (tw + ui.gap());
				int ty = cy + (i / perRow) * (th + ui.gap());
				ui.card(tx, ty, tw, th);
				ui.stat(tx + 10, ty + 8, tw - 20, tiles[i][0], tiles[i][1], tiles[i][2], i == 5 && o.newSuggestions > 0 ? ui.accent : ui.text);
			}
			cy += ((tiles.length + perRow - 1) / perRow) * (th + ui.gap()) + 4;

			int chartH = 140;
			ui.card(cx, cy, cw, chartH + 40);
			Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Daily launches and active users", "last 30 days, UTC");
			List<String> labels = new ArrayList<>();
			long[][] series = new long[3][o.daily.size()];
			for (int i = 0; i < o.daily.size(); i++) {
				Api.Daily d = o.daily.get(i);
				labels.add(d.day.substring(5));
				series[0][i] = d.launches;
				series[1][i] = d.active;
				series[2][i] = d.newInstalls;
			}
			int[] colors = {ui.chart[0], ui.chart[1], ui.chart[2]};
			Charts.lines(ui, "admin#daily", cx + 12, cy + 30, cw - 24, chartH - 10, labels, series, colors, Charts.Axis.COUNT,
				i -> o.daily.get(i).day + "\nLaunches " + o.daily.get(i).launches + "\nActive users " + o.daily.get(i).active + "\nNew users " + o.daily.get(i).newInstalls,
				"No launches recorded yet");
			Charts.legend(ui, cx + 12, cy + chartH + 24, new String[]{"Launches", "Active users", "New users"}, colors);
			cy += chartH + 40 + ui.gap();

			if (!o.versions.isEmpty()) {
				int rowH = ui.rowH();
				int vh = 34 + o.versions.size() * rowH + 8;
				ui.card(cx, cy, cw, vh);
				Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Versions in use", "active in 30 days");
				Charts.hbars(ui, "admin#versions", cx + 12, cy + 30, cw - 24, o.versions.stream().map(v -> v.version).toList(),
					o.versions.stream().mapToLong(v -> v.installs).toArray(), ui.chart[3], Fmt::count, rowH, null);
				cy += vh + ui.gap();
			}
		}

		cy += 6;
		ui.text("SUGGESTIONS INBOX", cx, cy + 5, ui.text3);
		int sel = ui.segmented("admin#filter", cx + cw - ui.segmentedWidth(FILTERS), cy, FILTERS, filter);
		if (sel >= 0 && sel != filter) {
			filter = sel;
			reloadInbox();
		}
		cy += ui.buttonH() + 10;
		Api.Suggestions s = inbox.get();
		if (s == null || inbox.loading() && s.suggestions.isEmpty()) {
			if (inbox.error() != null) {
				ui.text("Could not load suggestions: " + inbox.errorMessage(), cx, cy, ui.error);
			} else {
				ui.loading(cx, cy, cw, 80, "Loading suggestions…");
			}
			cy += 90;
		} else if (s.suggestions.isEmpty()) {
			ui.emptyState("admin#empty", cx, cy, cw, 120, Icons.BULB, "Inbox zero", "No suggestions in this view.", null);
			cy += 120;
		} else {
			for (Api.Suggestion sg : s.suggestions) {
				String text = sg.text == null ? "" : sg.text.replace("§", "");
				int ph = ui.paragraphHeight(text, cw - 24);
				int bh = ui.lineH() + 16 + ph + 10 + ui.buttonH() + 10;
				boolean isNew = "new".equals(sg.status);
				ui.panel(cx, cy, cw, bh, ui.surface, isNew ? Ui.withAlpha(ui.accent, 0.6f) : ui.border, ui.radius());
				int hx = cx + 12;
				String cat = switch (sg.category) {
					case "bug" -> "Bug";
					case "feature" -> "Feature";
					default -> "Other";
				};
				ui.badge(hx, cy + 8, cat, "bug".equals(sg.category) ? ui.warning : ui.accent);
				hx += ui.badgeW(cat) + 8;
				String meta = "#" + sg.id + " · " + Fmt.dateTime(sg.at) + " · " + (sg.player != null ? sg.player : "anonymous") + (sg.modVersion != null ? " · v" + sg.modVersion : "");
				ui.textClip(meta, hx, cy + 10, cx + cw - 12 - hx - 60, ui.text2);
				if (!"new".equals(sg.status)) {
					ui.textRight("done".equals(sg.status) ? "Done" : "Read", cx + cw - 12, cy + 10, "done".equals(sg.status) ? ui.success : ui.text3);
				}
				ui.paragraph(text, cx + 12, cy + ui.lineH() + 16, cw - 24, ui.text);
				int by = cy + bh - ui.buttonH() - 8;
				int bx = cx + 12;
				if (isNew) {
					int rw = ui.buttonWidth("Mark read", null);
					if (ui.button("admin#read" + sg.id, bx, by, rw, ui.buttonH(), "Mark read", Ui.Btn.SECONDARY)) {
						update(sg.id, "read", null);
					}
					bx += rw + 6;
				}
				boolean done = "done".equals(sg.status);
				String dl = done ? "Reopen" : "Mark done";
				int dw = ui.buttonWidth(dl, done ? null : Icons.CHECK);
				if (ui.button("admin#done" + sg.id, bx, by, dw, ui.buttonH(), dl, done ? null : Icons.CHECK, done ? Ui.Btn.SECONDARY : Ui.Btn.PRIMARY, true)) {
					update(sg.id, done ? "read" : "done", null);
				}
				bx += dw + 6;
				int cpw = ui.buttonWidth("Copy", Icons.COPY);
				if (ui.button("admin#copy" + sg.id, bx, by, cpw, ui.buttonH(), "Copy", Icons.COPY, Ui.Btn.GHOST, true)) {
					ui.copyToClipboard(text);
					screen.toast("Copied", SiqScreen.TOAST_SUCCESS);
				}
				int delW = ui.buttonWidth("Delete", Icons.TRASH);
				if (ui.button("admin#del" + sg.id, cx + cw - 12 - delW, by, delW, ui.buttonH(), "Delete", Icons.TRASH, Ui.Btn.DANGER, true)) {
					screen.confirm("Delete suggestion #" + sg.id + "?", "It is removed from the database permanently.", "Delete", true,
						() -> update(sg.id, "delete", "Suggestion deleted"));
				}
				cy += bh + ui.gap();
			}
		}
		ui.endScroll("admin#scroll", cy + off - y + pad);
	}

	/** Players connected right now. Pushed by the server on every connect and disconnect, so it is never stale. */
	private int renderOnlineNow(Ui ui, Api.AdminOverview o, int x, int y, int w) {
		Integer pushed = siq.online.onlineNow();
		long count = pushed != null ? pushed : o.onlineNow;
		boolean live = pushed != null;
		int h = ui.statH(true) + 18;
		ui.panel(x, y, w, h, Ui.argb(Ui.mix(ui.th.surface, ui.th.success, live ? 0.07f : 0f), 1f), live ? Ui.withAlpha(ui.success, 0.5f) : ui.border, ui.radius());
		float pulse = live && ui.animIntensity > 0 ? 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 600.0)) : 1f;
		ui.dot(x + 16, y + 14, 3, Ui.withAlpha(live ? ui.success : ui.text3, pulse));
		String sub = live ? "players with SessionIQ open right now · updates live" : "players online · live updates paused (reconnecting)";
		ui.stat(x + 26, y + 9, w - 36, "Online now", Fmt.count(count), sub, live ? ui.success : ui.text);
		String note = "Counts signed-in players. Players with online features off can't be counted.";
		if (w > ui.textW(sub) + ui.textW(note) + 80) {
			ui.textRight(note, x + w - 12, y + h - ui.lineH() - 8, ui.text3);
		}
		return h;
	}

	private void update(long id, String status, String success) {
		siq.online.post("/v1/admin/suggestions/update", Map.of("id", id, "status", status), JsonObject.class).whenCompleteAsync((r, e) -> {
			if (e != null) {
				screen.toast(ApiClient.message(e), SiqScreen.TOAST_ERROR);
				return;
			}
			if (success != null) {
				screen.toast(success, SiqScreen.TOAST_SUCCESS);
			}
			reload();
		}, SessionIQ.mainThread());
	}

	@Override
	public void onShow() {
		loadedAt = 0;
	}
}
