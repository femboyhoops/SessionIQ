package com.sessioniq.gui.pages;

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

import java.util.List;

public class GlobalPage extends Page {
	private static final long REFRESH_MS = 30_000;
	private final Async<Api.Global> data = new Async<>();
	private long loadedAt;
	private boolean loadedSignedIn;
	private int loadedSync;

	public GlobalPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Global Statistics";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Refresh", Icons.REFRESH);
		if (ui.button("global#refresh", right - bw, y, bw, h, "Refresh", Icons.REFRESH, Ui.Btn.SECONDARY, siq.online.active() && !data.loading())) {
			reload();
		}
		return right - bw - 6;
	}

	private void reload() {
		loadedAt = System.currentTimeMillis();
		data.reload(loadedAt, () -> siq.online.global());
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		if (OnlineGate.blocked(ui, screen, "global#gate", cx, y + pad, cw, h - pad * 2, false)) {
			return;
		}
		if (siq.online.signedIn() != loadedSignedIn || siq.online.syncGeneration() != loadedSync) {
			// Signed in, or your own stats were just uploaded: show the new numbers.
			loadedSignedIn = siq.online.signedIn();
			loadedSync = siq.online.syncGeneration();
			loadedAt = 0;
		}
		if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > REFRESH_MS && !data.loading()) {
			reload();
		}
		Api.Global g = data.get();
		if (g == null) {
			if (data.error() != null) {
				if (ui.emptyState("global#err", cx, y + pad, cw, h - pad * 2, Icons.WARNING, "Could not load Global Statistics", data.errorMessage(), "Try again")) {
					reload();
				}
			} else {
				ui.loading(cx, y + pad, cw, 160, "Loading Global Statistics…");
			}
			return;
		}
		int off = ui.beginScroll("global#scroll", x, y, w, h);
		int cy = y + pad - off;
		String sub = "Combined from every SessionIQ player who shares their statistics · updated " + Fmt.relative(g.generatedAt);
		ui.textClip(sub, cx, cy, cw, ui.text2);
		cy += ui.lineH() + 10;

		Api.ServerRow topTime = g.topServersByPlaytime.isEmpty() ? null : g.topServersByPlaytime.getFirst();
		Api.ServerRow topJoins = g.topServersByJoins.isEmpty() ? null : g.topServersByJoins.getFirst();
		String rank = g.you != null ? "#" + Fmt.count(g.you.rank) : "—";
		String rankSub = g.you != null ? Fmt.duration(g.you.playtimeMs) + " tracked"
			: !siq.online.signedIn() ? "sign in to be ranked" : !siq.config.get().shareGlobalStats ? "sharing is off" : "after your first sync";
		String[][] tiles = {
			{"Total playtime tracked", Fmt.duration(g.totals.playtimeMs), Fmt.plural(g.totals.sessions, "session", "sessions")},
			{"Players", Fmt.count(g.totals.players), "sharing statistics"},
			{"Servers tracked", Fmt.count(g.totals.servers), "public servers"},
			{"Your rank", rank, rankSub},
			{"Most tracked server", topTime == null ? "—" : topTime.host, topTime == null ? null : Fmt.duration(topTime.playtimeMs) + " · " + Fmt.plural(topTime.players, "player", "players")},
			{"Server with most joins", topJoins == null ? "—" : topJoins.host, topJoins == null ? null : Fmt.plural(topJoins.joins, "join", "joins")},
		};
		int perRow = cw >= 700 ? 3 : 2;
		int tw = (cw - ui.gap() * (perRow - 1)) / perRow;
		int th = ui.statH(true) + 16;
		for (int i = 0; i < tiles.length; i++) {
			int tx = cx + (i % perRow) * (tw + ui.gap());
			int ty = cy + (i / perRow) * (th + ui.gap());
			ui.card(tx, ty, tw, th);
			ui.stat(tx + 10, ty + 8, tw - 20, tiles[i][0], tiles[i][1], tiles[i][2], i >= 4 ? ui.accent : ui.text);
		}
		cy += ((tiles.length + perRow - 1) / perRow) * (th + ui.gap()) + 4;

		boolean two = cw >= 620;
		int colW = two ? (cw - ui.gap()) / 2 : cw;
		int rowH = ui.rowH();
		List<Api.ServerRow> byTime = g.topServersByPlaytime;
		List<Api.ServerRow> byJoins = g.topServersByJoins;
		int boxH = 34 + Math.max(1, Math.max(byTime.size(), two ? byJoins.size() : 0)) * rowH + 10;
		ui.card(cx, cy, colW, boxH);
		Widgets.heading(ui, cx + 12, cy + 10, colW - 24, "Most tracked servers", "by playtime");
		if (byTime.isEmpty()) {
			ui.text("No servers yet.", cx + 12, cy + 34, ui.text3);
		} else {
			Charts.hbars(ui, "global#time", cx + 12, cy + 30, colW - 24, byTime.stream().map(s -> s.host).toList(), byTime.stream().mapToLong(s -> s.playtimeMs).toArray(),
				ui.chart[0], Fmt::duration, rowH, i -> Fmt.plural(byTime.get(i).players, "player", "players") + " · " + Fmt.plural(byTime.get(i).joins, "join", "joins"));
		}
		int jx = two ? cx + colW + ui.gap() : cx;
		int jy = two ? cy : cy + boxH + ui.gap();
		int boxH2 = two ? boxH : 34 + Math.max(1, byJoins.size()) * rowH + 10;
		ui.card(jx, jy, colW, boxH2);
		Widgets.heading(ui, jx + 12, jy + 10, colW - 24, "Servers with most joins", null);
		if (byJoins.isEmpty()) {
			ui.text("No servers yet.", jx + 12, jy + 34, ui.text3);
		} else {
			Charts.hbars(ui, "global#joins", jx + 12, jy + 30, colW - 24, byJoins.stream().map(s -> s.host).toList(), byJoins.stream().mapToLong(s -> s.joins).toArray(),
				ui.chart[2], Fmt::count, rowH, i -> Fmt.duration(byJoins.get(i).playtimeMs) + " · " + Fmt.plural(byJoins.get(i).players, "player", "players"));
		}
		cy += two ? boxH + ui.gap() : boxH + boxH2 + 2 * ui.gap();

		List<Api.PlayerRow> players = g.topPlayers;
		int prow = ui.rowH() + 2;
		int listH = 34 + ui.lineH() + 6 + Math.max(1, players.size()) * prow + 8;
		ui.card(cx, cy, cw, listH);
		Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Players with the most data", "by tracked playtime");
		int[] col = Widgets.columns(cx + 12, cw - 24, 28, Math.max(90, (cw - 24) / 4), 90, 70, 70);
		int hy = cy + 30;
		String[] heads = {"#", "Player", "Playtime", "Sessions", "Servers", "Top server"};
		int[] hx = {col[0], col[1], col[2], col[3], col[4], col[4] + 70};
		boolean wide = cw >= 520;
		for (int i = 0; i < heads.length; i++) {
			if (wide || i < 3) {
				ui.text(heads[i], hx[i], hy, ui.text3);
			}
		}
		int ry = hy + ui.lineH() + 6;
		String me = siq.online.name();
		for (int i = 0; i < players.size(); i++) {
			Api.PlayerRow p = players.get(i);
			boolean mine = p.name.equalsIgnoreCase(me == null ? "" : me);
			if (mine) {
				ui.fill(cx + 4, ry - 1, cw - 8, prow, ui.selected);
			}
			ui.textMid(String.valueOf(i + 1), hx[0], ry, prow, i < 3 ? ui.accent : ui.text2);
			ui.textMid(ui.ellipsize(p.name + (mine ? " (you)" : ""), hx[2] - hx[1] - 8), hx[1], ry, prow, ui.text);
			ui.textMid(Fmt.duration(p.playtimeMs), hx[2], ry, prow, ui.text);
			if (wide) {
				ui.textMid(Fmt.count(p.sessions), hx[3], ry, prow, ui.text2);
				ui.textMid(Fmt.count(p.serversJoined), hx[4], ry, prow, ui.text2);
				ui.textMid(ui.ellipsize(p.topServer == null ? "—" : p.topServer, cx + cw - 12 - hx[5]), hx[5], ry, prow, ui.text2);
			}
			ry += prow;
		}
		if (players.isEmpty()) {
			ui.text("Nobody has shared statistics yet.", cx + 12, ry, ui.text3);
		}
		cy += listH + ui.gap();
		String note = "Updated within about a minute of players uploading their stats. Servers are counted under their main domain (eu.example.net counts as example.net). "
			+ "Only public server addresses are counted: IP addresses, LAN and private servers are never uploaded. Hide yourself from these lists in Settings › Online.";
		cy += ui.paragraph(note, cx, cy, cw, ui.text3);
		ui.endScroll("global#scroll", cy + off - y + pad);
	}

	@Override
	public void onShow() {
		loadedAt = 0;
		// Upload your latest stats (if they changed) so they show up here right away.
		siq.online.requestSync();
	}
}
