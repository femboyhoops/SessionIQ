package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.OnlineGate;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.online.Api;
import com.sessioniq.online.ApiClient;

import java.util.List;
import java.util.Map;

public class FriendsPage extends Page {
	private static final String ADD = "friends#add";
	private boolean sending;
	private String addMessage;
	private boolean addError;

	public FriendsPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Friends";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int bw = ui.buttonWidth("Refresh", Icons.REFRESH);
		if (ui.button("friends#refresh", right - bw, y, bw, h, "Refresh", Icons.REFRESH, Ui.Btn.SECONDARY, siq.online.signedIn() && !siq.online.friendsLoading())) {
			siq.online.loadFriends();
		}
		return right - bw - 6;
	}

	/** One-line description of what a friend is doing, honouring what they chose to share. */
	public static String describe(Api.Friend f) {
		if ("hidden".equals(f.status)) {
			return "Status hidden";
		}
		if (!f.online()) {
			return f.lastSeen == null ? "Offline" : "Last seen " + Fmt.relative(f.lastSeen);
		}
		if (f.activity == null) {
			return "Online";
		}
		if (f.activity.startsWith("server:")) {
			return "Playing on " + f.activity.substring(7);
		}
		return switch (f.activity) {
			case "singleplayer" -> "Playing single-player";
			case "private" -> "Playing on a private server";
			default -> "Online";
		};
	}

	public static int statusColor(Ui ui, Api.Friend f) {
		return f.online() ? ui.success : "hidden".equals(f.status) ? Ui.withAlpha(ui.text3, 0.4f) : ui.text3;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		if (OnlineGate.blocked(ui, screen, "friends#gate", cx, y + pad, cw, h - pad * 2, true)) {
			return;
		}
		// Changes arrive instantly over the live connection; poll only as a fallback.
		siq.online.ensureFriends(siq.online.liveConnected() ? 120_000 : 15_000);
		int off = ui.beginScroll("friends#scroll", x, y, w, h);
		int cy = y + pad - off;

		ui.text("Add a friend by their Minecraft username. They need SessionIQ with online features on.", cx, cy, ui.text2);
		cy += ui.lineH() + 6;
		TextField f = ui.field(ADD);
		f.maxLength = 16;
		f.filter = c -> c < 128 && (Character.isLetterOrDigit(c) || c == '_');
		int bw = ui.buttonWidth(sending ? "Sending…" : "Send request", Icons.PLUS);
		int fw = Math.min(260, cw - bw - 6);
		ui.textField(ADD, cx, cy, fw, 20, "Minecraft username", Icons.SEARCH);
		boolean submit = f.consumeSubmitted();
		if (f.consumeChanged()) {
			addMessage = null;
		}
		if (ui.button("friends#send", cx + fw + 6, cy + 1, bw, ui.buttonH(), sending ? "Sending…" : "Send request", Icons.PLUS, Ui.Btn.PRIMARY,
			!sending && !f.text.isBlank()) || submit && !sending && !f.text.isBlank()) {
			sendRequest(f);
		}
		cy += 26;
		if (addMessage != null) {
			cy += ui.paragraph(addMessage, cx, cy, cw, addError ? ui.error : ui.success) + 6;
		}
		cy += 6;

		Api.Friends data = siq.online.friends();
		if (data == null) {
			if (siq.online.friendsError() != null) {
				if (ui.emptyState("friends#err", cx, cy, cw, 140, Icons.WARNING, "Could not load friends", siq.online.friendsError(), "Try again")) {
					siq.online.loadFriends();
				}
				cy += 140;
			} else {
				ui.loading(cx, cy, cw, 100, "Loading friends…");
				cy += 100;
			}
			ui.endScroll("friends#scroll", cy + off - y + pad);
			return;
		}
		int rowH = ui.rowH() + 6;
		if (!data.incoming.isEmpty()) {
			int boxH = 34 + data.incoming.size() * rowH + 6;
			ui.card(cx, cy, cw, boxH);
			Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Friend requests", Fmt.count(data.incoming.size()));
			int ry = cy + 30;
			for (Api.PlayerRef r : data.incoming) {
				ui.textMid(r.name, cx + 12, ry, rowH, ui.text);
				ui.textMid(Fmt.relative(r.at), cx + 12 + Math.min(160, cw / 3), ry, rowH, ui.text3);
				int ax = cx + cw - 12;
				int blw = ui.buttonWidth("Block", null);
				ax -= blw;
				if (ui.button("friends#block" + r.uuid, ax, ry + (rowH - ui.buttonH()) / 2, blw, ui.buttonH(), "Block", Ui.Btn.GHOST)) {
					confirmBlock(r.uuid, r.name);
				}
				int dw = ui.buttonWidth("Decline", null);
				ax -= dw + 6;
				if (ui.button("friends#decline" + r.uuid, ax, ry + (rowH - ui.buttonH()) / 2, dw, ui.buttonH(), "Decline", Ui.Btn.SECONDARY)) {
					act("respond", Map.of("uuid", r.uuid, "accept", false), "Declined " + r.name);
				}
				int aw = ui.buttonWidth("Accept", Icons.CHECK);
				ax -= aw + 6;
				if (ui.button("friends#accept" + r.uuid, ax, ry + (rowH - ui.buttonH()) / 2, aw, ui.buttonH(), "Accept", Icons.CHECK, Ui.Btn.PRIMARY, true)) {
					act("respond", Map.of("uuid", r.uuid, "accept", true), r.name + " is now your friend");
				}
				ry += rowH;
			}
			cy += boxH + ui.gap();
		}

		List<Api.Friend> list = data.friends;
		long online = list.stream().filter(Api.Friend::online).count();
		int listH = 34 + Math.max(1, list.size()) * rowH + 6;
		ui.card(cx, cy, cw, listH);
		Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Friends", list.isEmpty() ? null : online + " online · " + list.size() + " total");
		int ry = cy + 30;
		if (list.isEmpty()) {
			ui.text("No friends yet. Send a request above.", cx + 12, ry + 4, ui.text3);
		}
		List<Api.Friend> sorted = list.stream().sorted((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name.compareToIgnoreCase(b.name)).toList();
		for (Api.Friend fr : sorted) {
			if (Widgets.row(ui, "friends#row" + fr.uuid, cx + 4, ry, cw - 8, rowH, false)) {
				screen.push(new FriendDetailPage(screen, fr.uuid, fr.name));
			}
			ui.dot(cx + 16, ry + rowH / 2, 3, statusColor(ui, fr));
			ui.textMid(fr.name, cx + 26, ry, rowH, ui.text);
			int nameW = Math.max(110, Math.min(170, cw / 4));
			int right = cx + cw - 16;
			ui.icon(Icons.CHEVRON_RIGHT, right - 9, ry + (rowH - 9) / 2, ui.text3);
			right -= 16;
			if (fr.unread > 0) {
				String b = fr.unread + " new";
				right -= ui.badgeW(b);
				ui.badge(right, ry + (rowH - ui.lineH() - 3) / 2, b, ui.accent);
				right -= 8;
			}
			ui.textMid(ui.ellipsize(describe(fr), right - (cx + 26 + nameW)), cx + 26 + nameW, ry, rowH, fr.online() ? ui.text2 : ui.text3);
			ry += rowH;
		}
		cy += listH + ui.gap();

		if (!data.outgoing.isEmpty()) {
			int boxH = 34 + data.outgoing.size() * rowH + 6;
			ui.card(cx, cy, cw, boxH);
			Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Sent requests", null);
			int oy = cy + 30;
			for (Api.PlayerRef r : data.outgoing) {
				ui.textMid(r.name, cx + 12, oy, rowH, ui.text);
				ui.textMid("Waiting · sent " + Fmt.relative(r.at), cx + 12 + Math.min(160, cw / 3), oy, rowH, ui.text3);
				int cwBtn = ui.buttonWidth("Cancel", null);
				if (ui.button("friends#cancel" + r.uuid, cx + cw - 12 - cwBtn, oy + (rowH - ui.buttonH()) / 2, cwBtn, ui.buttonH(), "Cancel", Ui.Btn.GHOST)) {
					act("cancel", Map.of("uuid", r.uuid), "Request to " + r.name + " cancelled");
				}
				oy += rowH;
			}
			cy += boxH + ui.gap();
		}

		if (!data.blocked.isEmpty()) {
			int boxH = 34 + data.blocked.size() * rowH + 6;
			ui.card(cx, cy, cw, boxH);
			Widgets.heading(ui, cx + 12, cy + 10, cw - 24, "Blocked", "they cannot send you requests or messages");
			int by = cy + 30;
			for (Api.PlayerRef r : data.blocked) {
				ui.textMid(r.name, cx + 12, by, rowH, ui.text2);
				int uw = ui.buttonWidth("Unblock", null);
				if (ui.button("friends#unblock" + r.uuid, cx + cw - 12 - uw, by + (rowH - ui.buttonH()) / 2, uw, ui.buttonH(), "Unblock", Ui.Btn.GHOST)) {
					act("unblock", Map.of("uuid", r.uuid), "Unblocked " + r.name);
				}
				by += rowH;
			}
			cy += boxH + ui.gap();
		}

		String note = "Friends only see what you allow: online status, the public server you are on, your stats and chat can each be turned off.";
		cy += ui.paragraph(note, cx, cy, cw, ui.text3) + 4;
		if (ui.link("friends#privacy", "Friend privacy settings", cx, cy, ui.accent)) {
			OnlineGate.openOnlineSettings(screen);
		}
		cy += ui.lineH() + 4;
		ui.endScroll("friends#scroll", cy + off - y + pad);
	}

	private void sendRequest(TextField f) {
		String name = f.text.trim();
		sending = true;
		addMessage = null;
		siq.online.requestFriend(name).whenCompleteAsync((r, e) -> {
			sending = false;
			if (e != null) {
				addError = true;
				addMessage = ApiClient.message(e);
				return;
			}
			addError = false;
			addMessage = "friends".equals(r.status) ? r.name + " had already sent you a request, so you are now friends." : "Friend request sent to " + r.name + ".";
			f.set("");
		}, SessionIQ.mainThread());
	}

	private void act(String action, Map<String, Object> body, String success) {
		siq.online.friendAction(action, body).whenCompleteAsync((r, e) -> screen.toast(e == null ? success : ApiClient.message(e),
			e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR), SessionIQ.mainThread());
	}

	@Override
	public void onShow() {
		if (siq.online.signedIn() && !siq.online.friendsLoading()) {
			siq.online.loadFriends();
		}
	}

	void confirmBlock(String uuid, String name) {
		screen.confirm("Block " + name + "?", name + " is removed from your friends and can no longer send you friend requests or messages. They are not told that you blocked them.",
			"Block", true, () -> act("block", Map.of("uuid", uuid), "Blocked " + name));
	}
}
