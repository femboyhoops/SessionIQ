package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.gui.pages.SettingsPage;
import com.sessioniq.online.OnlineService;

/** Shared "why can't I use this yet" states for the online pages. */
public final class OnlineGate {
	private OnlineGate() {
	}

	/**
	 * Draws the reason an online page cannot be shown and returns true, or returns false when the
	 * page can render. Pages that need an account pass needsAccount = true.
	 */
	public static boolean blocked(Ui ui, SiqScreen screen, String id, int x, int y, int w, int h, boolean needsAccount) {
		OnlineService online = SessionIQ.get().online;
		switch (online.status()) {
			case UNAVAILABLE -> {
				ui.emptyState(id, x, y, w, h, Icons.GLOBE, "Online features unavailable", "This build of SessionIQ was made without an online service.", null);
				return true;
			}
			case DISABLED -> {
				if (ui.emptyState(id, x, y, w, h, Icons.GLOBE, "Online features are off", "Turn them on in Settings › Online.", "Open settings")) {
					openOnlineSettings(screen);
				}
				return true;
			}
			case NEEDS_NOTICE -> {
				if (ui.emptyState(id, x, y, w, h, Icons.EYE, "Review what is shared", "Before anything is uploaded, read what other players can see.", "Review")) {
					screen.openModal(new OnlineNoticeModal(screen));
				}
				return true;
			}
			case SIGNING_IN -> {
				if (needsAccount) {
					ui.loading(x, y, w, Math.min(h, 160), "Signing in with your Minecraft account…");
					return true;
				}
				return false;
			}
			case SIGNED_OUT -> {
				if (needsAccount) {
					if (ui.emptyState(id, x, y, w, h, Icons.WARNING, "Not signed in", online.signInError(), "Try again")) {
						online.retrySignIn();
					}
					return true;
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	public static void openOnlineSettings(SiqScreen screen) {
		screen.selectSection("settings");
		screen.root("settings", SettingsPage.class).showTab("Online");
	}
}
