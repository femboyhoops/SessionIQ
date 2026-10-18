package com.sessioniq.gui;

import com.sessioniq.SessionIQ;

import java.util.List;

/**
 * The first-launch notice. Nothing is uploaded until the player presses "Got it"; closing it
 * any other way leaves online features paused, and it is shown again on the next launch.
 */
public class OnlineNoticeModal extends Modal {
	static final String INTRO = "SessionIQ keeps your full history on your computer. Its online features (Global Statistics, Friends and Suggestions) use a summary "
		+ "linked to your Minecraft username. Other SessionIQ users can see:";
	static final List<String> PUBLIC = List.of(
		"Your Minecraft username",
		"Total playtime tracked",
		"Number of sessions",
		"Number of servers joined and total server joins",
		"Your most-played server and your playtime there (public servers only)",
		"Your rank on the leaderboards");
	static final String FRIENDS = "Friends you accept can also see whether you are online, which public server you are on, and your stats, and can chat with you.";
	static final String NEVER = "Never uploaded: your chat logs, disconnects, timelines, single-player worlds, and private or LAN server addresses. "
		+ "Launches are also counted anonymously (a random install ID plus the mod and Minecraft versions).";
	static final String SIGN_IN = "To sign in, SessionIQ proves the account is yours with the Mojang-signed key Minecraft already uses for secure chat, "
		+ "and looks up your username by account ID on playerdb.co. Your Minecraft login is never sent to SessionIQ.";
	static final String CHANGE = "You can change any of this, or delete your shared data, at any time in Settings › Online.";

	private final SiqScreen screen;

	public OnlineNoticeModal(SiqScreen screen) {
		this.screen = screen;
	}

	@Override
	public String title() {
		return "Welcome to SessionIQ";
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 400;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		int h = 6 + ui.paragraphHeight(INTRO, w) + 6;
		for (String p : PUBLIC) {
			h += ui.paragraphHeight(p, w - 12) + 2;
		}
		h += 8;
		for (String p : new String[]{FRIENDS, NEVER, SIGN_IN, CHANGE}) {
			h += ui.paragraphHeight(p, w) + 8;
		}
		return h;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int cy = y + 6;
		cy += ui.paragraph(INTRO, x, cy, w, ui.text) + 6;
		for (String p : PUBLIC) {
			ui.fill(x + 3, cy + 3, 3, 3, ui.accent);
			cy += ui.paragraph(p, x + 12, cy, w - 12, ui.text) + 2;
		}
		cy += 8;
		cy += ui.paragraph(FRIENDS, x, cy, w, ui.text2) + 8;
		cy += ui.paragraph(NEVER, x, cy, w, ui.text2) + 8;
		cy += ui.paragraph(SIGN_IN, x, cy, w, ui.text2) + 8;
		ui.paragraph(CHANGE, x, cy, w, ui.text2);
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		int okW = ui.buttonWidth("Got it", null) + 10;
		if (ui.button("notice#ok", x + w - okW, y, okW, h, "Got it", Ui.Btn.PRIMARY)) {
			close();
			SessionIQ.get().online.acceptNotice();
			screen.toast("Online features are on. Change what you share in Settings › Online.", SiqScreen.TOAST_SUCCESS);
		}
		int sw = ui.buttonWidth("Settings…", null);
		if (ui.button("notice#settings", x + w - okW - sw - 6, y, sw, h, "Settings…", Ui.Btn.SECONDARY)) {
			close();
			OnlineGate.openOnlineSettings(screen);
		}
		int vw = ui.buttonWidth("View what's sent", Icons.EYE);
		if (ui.button("notice#view", x, y, vw, h, "View what's sent", Icons.EYE, Ui.Btn.GHOST, true)) {
			screen.openModal(new WhatsSentModal());
		}
	}
}
