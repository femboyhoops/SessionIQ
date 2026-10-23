package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.OnlineGate;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.online.Api;
import com.sessioniq.online.ApiClient;
import net.minecraft.text.OrderedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A friend's shared stats and the chat with them. */
public class FriendDetailPage extends Page {
	/** New messages normally arrive over the live connection; these polls are the fallback. */
	private static final long POLL_MS = 4_000;
	private static final long LIVE_POLL_MS = 30_000;
	private final String uuid;
	private final String fallbackName;
	private final String inputId;
	private final List<Api.Message> messages = new ArrayList<>();
	private long lastId;
	private long nextPollAt;
	private boolean polling;
	private boolean sending;
	private String chatError;
	private boolean loaded;
	private int retentionDays = 7;
	private boolean jumpToEnd = true;
	private boolean followNew;
	private boolean atBottom = true;
	private int lastSignal = -1;

	public FriendDetailPage(SiqScreen screen, String uuid, String name) {
		super(screen);
		this.uuid = uuid;
		this.fallbackName = name;
		this.inputId = "friend#input" + uuid;
	}

	private Api.Friend friend() {
		return siq.online.friend(uuid);
	}

	private String name() {
		Api.Friend f = friend();
		return f != null ? f.name : fallbackName;
	}

	@Override
	public String title() {
		return name();
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		if (!siq.online.signedIn() || friend() == null) {
			return right;
		}
		int bw = ui.buttonWidth("Block", null);
		if (ui.button("friend#block", right - bw, y, bw, h, "Block", Ui.Btn.DANGER)) {
			String n = name();
			screen.confirm("Block " + n + "?", n + " is removed from your friends and can no longer send you friend requests or messages. They are not told that you blocked them.",
				"Block", true, () -> act("block", "Blocked " + n));
		}
		int rw = ui.buttonWidth("Remove friend", null);
		if (ui.button("friend#remove", right - bw - rw - 6, y, rw, h, "Remove friend", Ui.Btn.SECONDARY)) {
			String n = name();
			screen.confirm("Remove " + n + "?", "You will no longer see each other's status or stats, and your chat is closed. You can send a new request later.",
				"Remove", true, () -> act("remove", "Removed " + n));
		}
		return right - bw - rw - 12;
	}

	private void act(String action, String success) {
		siq.online.friendAction(action, Map.of("uuid", uuid)).whenCompleteAsync((r, e) -> {
			screen.toast(e == null ? success : ApiClient.message(e), e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR);
			if (e == null) {
				screen.back();
			}
		}, SessionIQ.mainThread());
	}

	private void poll() {
		Api.Friend f = friend();
		if (polling || f == null || System.currentTimeMillis() < nextPollAt) {
			return;
		}
		polling = true;
		siq.online.get("/v1/chat?with=" + uuid + "&after=" + lastId, Api.Conversation.class).whenCompleteAsync((c, e) -> {
			polling = false;
			nextPollAt = System.currentTimeMillis() + (siq.online.liveConnected() ? LIVE_POLL_MS : POLL_MS);
			if (e != null) {
				chatError = ApiClient.message(e);
				return;
			}
			chatError = null;
			loaded = true;
			retentionDays = c.retentionDays > 0 ? c.retentionDays : retentionDays;
			addMessages(c.messages);
			siq.online.markRead(uuid);
		}, SessionIQ.mainThread());
	}

	private void addMessages(List<Api.Message> incoming) {
		boolean first = messages.isEmpty();
		for (Api.Message m : incoming) {
			if (m.id > lastId) {
				messages.add(m);
				lastId = m.id;
				if (first) {
					// Opening the chat: start at the newest messages.
					jumpToEnd = true;
				} else if (atBottom || m.mine) {
					// Follow new messages unless the player scrolled up to read older ones.
					followNew = true;
				}
			}
		}
		while (messages.size() > 500) {
			messages.removeFirst();
		}
	}

	private void send(TextField field) {
		String text = field.text.trim();
		if (text.isEmpty() || sending) {
			return;
		}
		sending = true;
		siq.online.post("/v1/chat/send", Map.of("to", uuid, "text", text), Api.Message.class).whenCompleteAsync((m, e) -> {
			sending = false;
			if (e != null) {
				screen.toast(ApiClient.message(e), SiqScreen.TOAST_ERROR);
				return;
			}
			field.set("");
			nextPollAt = 0;
			poll();
		}, SessionIQ.mainThread());
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		if (OnlineGate.blocked(ui, screen, "friend#gate", cx, y + pad, cw, h - pad * 2, true)) {
			return;
		}
		siq.online.ensureFriends(60_000);
		Api.Friend f = friend();
		if (f == null) {
			if (siq.online.friends() == null || siq.online.friendsLoading()) {
				ui.loading(cx, y + pad, cw, 120, "Loading…");
			} else {
				ui.emptyState("friend#gone", cx, y + pad, cw, h - pad * 2, Icons.FRIENDS, "Not your friend anymore", fallbackName + " is no longer on your friends list.", null);
			}
			return;
		}
		siq.online.viewingChat(uuid);
		int signal = siq.online.messageSignal(uuid);
		if (signal != lastSignal) {
			lastSignal = signal;
			nextPollAt = 0;
		}
		poll();
		int cy = y + pad;
		ui.dot(cx + 4, cy + ui.lineH() / 2, 3, FriendsPage.statusColor(ui, f));
		ui.textClip(FriendsPage.describe(f), cx + 14, cy, cw - 14, ui.text2);
		cy += ui.lineH() + 4;
		ui.text("Friends since " + Fmt.date(f.since), cx + 14, cy, ui.text3);
		cy += ui.lineH() + 10;

		boolean wide = cw >= 640;
		int statsW = wide ? Math.min(260, cw / 3) : cw;
		int statsH = renderStats(ui, f, cx, cy, statsW, wide);
		int chatX = wide ? cx + statsW + ui.gap() : cx;
		int chatY = wide ? cy : cy + statsH + ui.gap();
		int chatW = wide ? cw - statsW - ui.gap() : cw;
		int chatH = Math.max(120, y + h - pad - chatY);
		renderChat(ui, f, chatX, chatY, chatW, chatH);
	}

	private int renderStats(Ui ui, Api.Friend f, int x, int y, int w, boolean tall) {
		Api.FriendStats s = f.stats;
		if (s == null) {
			String msg = f.name + " does not share their stats with friends.";
			int hh = ui.paragraphHeight(msg, w - 24) + 44;
			ui.card(x, y, w, hh);
			ui.text("Stats", x + 12, y + 10, ui.text);
			ui.paragraph(msg, x + 12, y + 28, w - 24, ui.text3);
			return hh;
		}
		String[][] rows = {
			{"Playtime tracked", Fmt.duration(s.playtimeMs)},
			{"Sessions", Fmt.count(s.sessions)},
			{"Servers joined", Fmt.count(s.serversJoined)},
			{"Server joins", Fmt.count(s.totalJoins)},
			{"Most played", s.topServer == null ? "—" : s.topServer},
			{"Time there", s.topServer == null ? "—" : Fmt.duration(s.topServerMs)},
		};
		int rh = ui.lineH() + 8;
		if (tall) {
			int hh = 34 + rows.length * rh + ui.lineH() + 12;
			ui.card(x, y, w, hh);
			ui.text("Stats", x + 12, y + 10, ui.text);
			int ry = y + 32;
			for (String[] r : rows) {
				ui.text(r[0], x + 12, ry, ui.text2);
				ui.textRight(ui.ellipsize(r[1], w - 24 - ui.textW(r[0]) - 10), x + w - 12, ry, ui.text);
				ry += rh;
			}
			ui.text("Updated " + Fmt.relative(s.updatedAt), x + 12, ry + 4, ui.text3);
			return hh;
		}
		int per = w >= 420 ? 3 : 2;
		int tw = (w - 24) / per;
		int lines = (rows.length + per - 1) / per;
		int hh = 32 + lines * (ui.lineH() * 2 + 10) + 4;
		ui.card(x, y, w, hh);
		ui.text("Stats", x + 12, y + 10, ui.text);
		ui.textRight("updated " + Fmt.relative(s.updatedAt), x + w - 12, y + 10, ui.text3);
		for (int i = 0; i < rows.length; i++) {
			int tx = x + 12 + (i % per) * tw;
			int ty = y + 30 + (i / per) * (ui.lineH() * 2 + 10);
			ui.text(rows[i][0], tx, ty, ui.text3);
			ui.textClip(rows[i][1], tx, ty + ui.lineH() + 3, tw - 8, ui.text);
		}
		return hh;
	}

	private void renderChat(Ui ui, Api.Friend f, int x, int y, int w, int h) {
		ui.card(x, y, w, h);
		ui.text("Chat", x + 12, y + 10, ui.text);
		ui.textRight("deleted from the server after " + retentionDays + " days", x + w - 12, y + 10, ui.text3);
		int inputH = 22;
		int listY = y + 28;
		int listH = h - 28 - inputH - 16;
		String sid = "friend#chat" + uuid;
		int bubbleMax = Math.max(120, (w - 24) * 7 / 10);
		int total = 6;
		List<List<OrderedText>> wrapped = new ArrayList<>();
		for (Api.Message m : messages) {
			List<OrderedText> lines = ui.wrap(clean(m.text), bubbleMax - 12);
			wrapped.add(lines);
			total += lines.size() * (ui.lineH() + 1) + 10 + ui.lineH() + 6;
		}
		int off = ui.beginScroll(sid, x + 1, listY, w - 2, listH);
		int my = listY + 6 - off;
		if (messages.isEmpty()) {
			String empty = !loaded ? (chatError != null ? chatError : "Loading messages…") : "No messages yet. Say hi!";
			ui.textCenter(empty, x + w / 2, listY + listH / 2 - ui.lineH() / 2, chatError != null ? ui.error : ui.text3);
		}
		for (int i = 0; i < messages.size(); i++) {
			Api.Message m = messages.get(i);
			List<OrderedText> lines = wrapped.get(i);
			int bw = 12;
			for (OrderedText l : lines) {
				bw = Math.max(bw, ui.textW(l) + 12);
			}
			int bh = lines.size() * (ui.lineH() + 1) + 8;
			int bx = m.mine ? x + w - 12 - bw : x + 12;
			if (ui.visible(my, bh + ui.lineH() + 6)) {
				int fill = m.mine ? Ui.withAlpha(ui.accent, 0.22f) : ui.surface2;
				ui.panel(bx, my, bw, bh, fill, m.mine ? Ui.withAlpha(ui.accent, 0.4f) : ui.border, Math.min(4, ui.th.radius + 1));
				for (int l = 0; l < lines.size(); l++) {
					ui.text(lines.get(l), bx + 6, my + 4 + l * (ui.lineH() + 1), ui.text);
				}
				String meta = (m.mine ? "You" : f.name) + " · " + Fmt.time(m.at) + (Fmt.localDate(m.at).equals(Fmt.localDate(System.currentTimeMillis())) ? "" : " " + Fmt.date(m.at));
				if (m.mine) {
					ui.textRight(meta, x + w - 12, my + bh + 2, ui.text3);
				} else {
					ui.text(meta, bx, my + bh + 2, ui.text3);
				}
			}
			my += bh + ui.lineH() + 6;
		}
		ui.endScroll(sid, total);
		// Scrolling is applied after endScroll, once the list knows its height; before that,
		// "the end" of a list that has not been measured yet is its top.
		if (jumpToEnd && !messages.isEmpty()) {
			ui.jumpToEnd(sid);
			jumpToEnd = false;
		} else if (followNew) {
			ui.scrollTo(sid, total);
			followNew = false;
		}
		atBottom = total <= listH || ui.nearBottom(sid, 24);

		int iy = y + h - inputH - 8;
		if (!f.canChat) {
			String why = !siq.config.get().friendsAllowChat ? "You turned friend chat off in Settings › Online." : f.name + " has chat turned off.";
			ui.textClip(why, x + 12, iy + (inputH - ui.capH()) / 2, w - 24, ui.text3);
			return;
		}
		TextField field = ui.field(inputId);
		field.maxLength = 256;
		field.filter = c -> c >= 32 && c != 127 && c != '§';
		int sw = ui.buttonWidth(sending ? "Sending…" : "Send", Icons.SEND);
		ui.textField(inputId, x + 12, iy, w - 24 - sw - 6, inputH, "Message " + f.name, null);
		boolean submit = field.consumeSubmitted();
		if (ui.button("friend#send", x + w - 12 - sw, iy + (inputH - ui.buttonH()) / 2, sw, ui.buttonH(), sending ? "Sending…" : "Send", Icons.SEND, Ui.Btn.PRIMARY,
			!sending && !field.text.isBlank()) || submit) {
			send(field);
		}
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace("§", "");
	}

	@Override
	public void onShow() {
		nextPollAt = 0;
		jumpToEnd = true;
	}
}
