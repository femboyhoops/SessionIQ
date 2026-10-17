package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.storage.ChatArchive;
import com.sessioniq.storage.DataActions;
import net.minecraft.text.OrderedText;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ChatMessagePage extends Page {
	private static final int CONTEXT = 12;
	private final ChatRecord record;
	private final Async<List<ChatRecord>> context = new Async<>();
	private boolean deleted;

	public ChatMessagePage(SiqScreen screen, ChatRecord record) {
		super(screen);
		this.record = record;
	}

	@Override
	public String title() {
		return "Message · " + Fmt.dateTimeSeconds(record.at);
	}

	@Override
	public String crumb() {
		return "Message";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int x = right;
		int dw = ui.buttonWidth("Delete", Icons.TRASH);
		x -= dw;
		if (ui.button("msg#delete", x, y, dw, h, "Delete", Icons.TRASH, Ui.Btn.DANGER, !deleted)) {
			screen.confirm("Delete this message?", "The message is permanently removed from the archive and from session and server counts.", "Delete", true,
				() -> DataActions.deleteChat(siq, List.of(record)).whenCompleteAsync((n, err) -> {
					if (err == null) {
						deleted = true;
						screen.toast("Message deleted", SiqScreen.TOAST_SUCCESS);
						screen.back();
					} else {
						screen.toast("Could not delete: " + err.getMessage(), SiqScreen.TOAST_ERROR);
					}
				}, SessionIQ.mainThread()));
		}
		x -= 6;
		int cw = ui.buttonWidth("Copy", Icons.COPY);
		x -= cw;
		if (ui.button("msg#copy", x, y, cw, h, "Copy", Icons.COPY, Ui.Btn.SECONDARY, true)) {
			ui.copyToClipboard(record.text);
			screen.toast("Message copied", SiqScreen.TOAST_INFO);
		}
		return x - 6;
	}

	private void loadContext() {
		context.ensure(record.id, () -> siq.storage.submitQuery(() -> {
			Deque<ChatRecord> before = new ArrayDeque<>();
			List<ChatRecord> out = new ArrayList<>();
			boolean found = false;
			int after = 0;
			for (Path p : siq.chat.partitions(record.sessionId)) {
				List<ChatRecord> part = new ArrayList<>(ChatArchive.readPartition(p).records());
				part.sort((a, b) -> Long.compare(a.at, b.at));
				for (ChatRecord r : part) {
					if (!found) {
						if (r.id.equals(record.id)) {
							found = true;
							out.addAll(before);
							out.add(r);
						} else {
							before.addLast(r);
							if (before.size() > CONTEXT) {
								before.removeFirst();
							}
						}
					} else if (after < CONTEXT) {
						out.add(r);
						after++;
					}
				}
				if (found && after >= CONTEXT) {
					break;
				}
			}
			return out;
		}));
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		loadContext();
		int pad = ui.pad() + 4;
		int off = ui.beginScroll("msg#scroll", x, y, w, h);
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad - off;

		List<OrderedText> lines = ui.wrap(record.text, cw - 24);
		int msgH = 18 + ui.lineH() + 6 + lines.size() * (ui.lineH() + 2) + 10;
		ui.card(cx, cy, cw, msgH);
		String who = record.isSystem() ? "System message" : record.sender != null ? record.sender : "Sender unknown";
		ui.text(who, cx + 12, cy + 10, record.isSystem() ? ui.text2 : ui.text);
		ChatCategory cat = ChatCategory.parse(record.category);
		ui.badge(cx + cw - 12 - ui.badgeW(cat.label), cy + 8, cat.label, record.isSystem() ? ui.text2 : ui.accent);
		int ly = cy + 16 + ui.lineH();
		for (OrderedText l : lines) {
			ui.text(l, cx + 12, ly, ui.text);
			ly += ui.lineH() + 2;
		}
		cy += msgH + ui.gap();

		SessionRecord s = siq.sessions.get(record.sessionId);
		boolean two = cw >= 520;
		int colW = two ? (cw - ui.gap()) / 2 : cw;
		int fy = cy + 10;
		int metaH = 0;
		int left = cx + 12;
		int fw = colW - 24;
		int h1 = 0;
		h1 += Widgets.field(ui, left, fy + h1, fw, "Received", Fmt.dateTimeSeconds(record.at));
		h1 += Widgets.field(ui, left, fy + h1, fw, "Server", ServerNames.name(record.serverKey) + " (" + ServerNames.address(record.serverKey) + ")");
		h1 += Widgets.field(ui, left, fy + h1, fw, "Origin", record.isSystem() ? "System message (shown in chat by the server or game)" : "Player chat");
		int rx = two ? cx + colW + ui.gap() + 12 : left;
		int ry = two ? fy : fy + h1;
		int h2 = 0;
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Sender UUID", record.senderUuid == null ? "Not available" : record.senderUuid);
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Session", s == null ? record.sessionId + " (deleted)" : Fmt.dateTime(s.startedAt) + " · " + record.sessionId);
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Message ID", record.id);
		metaH = two ? Math.max(h1, h2) : h1 + h2;
		cy += metaH + 16;
		int lx = cx;
		if (s != null && ui.link("msg#session", "Open session", lx, cy, ui.accent)) {
			screen.push(new SessionDetailPage(screen, record.sessionId));
		}
		lx += ui.textW("Open session") + 16;
		if (record.serverKey != null && ui.link("msg#server", "Open server", lx, cy, ui.accent)) {
			screen.push(new ServerDetailPage(screen, record.serverKey));
		}
		cy += ui.lineH() + 14;

		ui.text("CONVERSATION", cx, cy, ui.text3);
		cy += ui.lineH() + 6;
		List<ChatRecord> ctx = context.get();
		if (ctx == null) {
			ui.loading(cx, cy, cw, 60, "Loading surrounding messages…");
			cy += 60;
		} else {
			int lh = ui.lineH() + 2;
			int textX = cx + 12 + ui.textW("00:00:00 PM") + 8;
			int textW = cx + cw - textX - 10;
			int cardY = cy;
			int total = 0;
			List<List<OrderedText>> wrapped = new ArrayList<>();
			for (ChatRecord r : ctx) {
				List<OrderedText> wl = ui.wrap(r.text, textW);
				wrapped.add(wl);
				total += wl.size() * lh + 6;
			}
			ui.card(cx, cardY, cw, total + 8);
			int my = cardY + 4;
			for (int i = 0; i < ctx.size(); i++) {
				ChatRecord r = ctx.get(i);
				int rh = wrapped.get(i).size() * lh + 6;
				boolean me = r.id.equals(record.id);
				if (me) {
					ui.fill(cx + 1, my, cw - 2, rh, ui.selected);
					ui.fill(cx + 1, my, 2, rh, ui.accent);
				} else if (Widgets.row(ui, "msg#ctx" + r.id, cx + 1, my, cw - 2, rh, false)) {
					screen.push(new ChatMessagePage(screen, r));
				}
				ui.text(Fmt.timeSeconds(r.at), cx + 12, my + 3, ui.text3);
				for (int l = 0; l < wrapped.get(i).size(); l++) {
					ui.text(wrapped.get(i).get(l), textX, my + 3 + l * lh, r.isSystem() ? ui.text2 : ui.text);
				}
				my += rh;
			}
			cy = cardY + total + 8;
		}
		ui.endScroll("msg#scroll", cy + off - y + pad);
	}
}
