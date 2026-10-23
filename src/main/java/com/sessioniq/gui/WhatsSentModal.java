package com.sessioniq.gui;

import com.google.gson.JsonObject;
import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.util.Json;

import java.util.ArrayList;
import java.util.List;

/** Shows the exact request bodies the mod sends, built from the player's current history and settings. */
public class WhatsSentModal extends Modal {
	private record Block(String heading, String note, List<String> lines) {
	}

	private final List<Block> blocks = new ArrayList<>();

	public WhatsSentModal() {
		SessionIQ siq = SessionIQ.get();
		SiqConfig c = siq.config.get();
		if (c.shareGlobalStats || c.friendsShowStats) {
			blocks.add(new Block("Stats summary", "Sent when it changes: every 30 minutes at most, and after you leave a server. "
				+ (c.shareGlobalStats ? "The server list feeds Global Statistics." : "Global Statistics is off, so no servers are listed.")
				+ " The device ID makes your accounts on this computer count as one player.",
				lines(Json.PRETTY.toJson(siq.online.currentSnapshot()))));
		} else {
			blocks.add(new Block("Stats summary", "Not sent: both Global Statistics and friend stats are turned off.", List.of()));
		}
		JsonObject presence = new JsonObject();
		presence.addProperty("online", c.friendsShowOnline);
		if (c.friendsShowOnline) {
			presence.addProperty("activity", c.friendsShowServer ? "server" : "hidden");
			if (c.friendsShowServer) {
				presence.addProperty("server", "<public server you are on>");
			}
		}
		blocks.add(new Block("Friends heartbeat", "Sent every 2 minutes while signed in (every 10 minutes if you have no friends yet), so friends see your status and you receive "
			+ "messages. On a private or LAN server, or in single-player, only that is sent, never the address.", lines(Json.PRETTY.toJson(presence))));
		blocks.add(new Block("Live connection", "While signed in, the game keeps one connection open so friend requests, messages and status changes arrive "
			+ "instantly. The game only sends \"ping\" on it every 40 seconds; it receives short \"something changed\" notices.", List.of()));
		JsonObject launch = new JsonObject();
		launch.addProperty("installId", "a random ID, not linked to your account");
		launch.addProperty("modVersion", siq.modVersion);
		launch.addProperty("mcVersion", siq.mcVersion);
		blocks.add(new Block("Launch counter", "Sent once per launch, anonymously.", lines(Json.PRETTY.toJson(launch))));
		JsonObject privacy = new JsonObject();
		privacy.addProperty("shareStats", c.shareGlobalStats);
		privacy.addProperty("showOnline", c.friendsShowOnline);
		privacy.addProperty("showServer", c.friendsShowServer);
		privacy.addProperty("showStats", c.friendsShowStats);
		privacy.addProperty("allowChat", c.friendsAllowChat);
		blocks.add(new Block("Your privacy settings", "Sent when you change them, so the server can apply them.", lines(Json.PRETTY.toJson(privacy))));
	}

	private static List<String> lines(String json) {
		return List.of(json.split("\n"));
	}

	@Override
	public String title() {
		return "What SessionIQ sends";
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 420;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		int h = 6;
		for (Block b : blocks) {
			h += ui.lineH() + 4 + ui.paragraphHeight(b.note(), w) + 4;
			if (!b.lines().isEmpty()) {
				h += b.lines().size() * (ui.lineH() + 1) + 10;
			}
			h += 8;
		}
		return h;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int cy = y + 6;
		for (Block b : blocks) {
			ui.text(b.heading(), x, cy, ui.text);
			cy += ui.lineH() + 4;
			cy += ui.paragraph(b.note(), x, cy, w, ui.text2) + 4;
			if (!b.lines().isEmpty()) {
				int bh = b.lines().size() * (ui.lineH() + 1) + 8;
				ui.panel(x, cy, w, bh, Ui.argb(Ui.mix(ui.th.bg, ui.th.surface, 0.5f), 1f), ui.border, Math.min(3, ui.th.radius));
				int ly = cy + 4;
				for (String l : b.lines()) {
					ui.textClip(l.replace("\t", "  "), x + 6, ly, w - 12, ui.text2);
					ly += ui.lineH() + 1;
				}
				cy += bh + 2;
			}
			cy += 8;
		}
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		if (ui.button("sent#ok", x + w - 60, y, 60, h, "Close", Ui.Btn.PRIMARY)) {
			close();
		}
	}
}
