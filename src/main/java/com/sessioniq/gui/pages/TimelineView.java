package com.sessioniq.gui.pages;

import com.sessioniq.analytics.TimelineEvent;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.model.ChatRecord;
import net.minecraft.text.OrderedText;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TimelineView {
	private final String id;
	private final Set<String> expanded = new HashSet<>();
	private final Map<String, Integer> shownCount = new HashMap<>();

	public TimelineView(String id) {
		this.id = id;
	}

	public static Icons icon(TimelineEvent.Type t) {
		return switch (t) {
			case SESSION_START -> Icons.PLUG;
			case SESSION_END -> Icons.DOT;
			case SESSION_RECOVERED -> Icons.WARNING;
			case SERVER_JOIN -> Icons.JOIN;
			case SERVER_LEAVE -> Icons.LEAVE;
			case DISCONNECT -> Icons.DISCONNECTS;
			case CONNECTION_FAILED -> Icons.WARNING;
			case RECONNECTED -> Icons.REFRESH;
			case CHAT -> Icons.CHAT;
			case DEATH -> Icons.DEATH;
		};
	}

	public static int color(Ui ui, TimelineEvent e) {
		if (e.severity == 2) {
			return ui.error;
		}
		if (e.severity == 1) {
			return ui.warning;
		}
		return switch (e.type) {
			case SERVER_JOIN, RECONNECTED -> ui.success;
			case CHAT, SESSION_START -> ui.accent;
			default -> ui.text2;
		};
	}

	private static String groupKey(TimelineEvent e) {
		return e.sessionId + ":" + e.chatFrom;
	}

	public int render(Ui ui, SiqScreen screen, int x, int y, int w, List<TimelineEvent> events, Map<String, List<ChatRecord>> chat,
					  String currentSession) {
		int cy = y;
		int timeW = Math.max(ui.textW("00:00:00 PM"), 48) + 6;
		int railX = x + timeW + 8;
		int textX = railX + 16;
		int textW = x + w - textX - 4;
		int lastHour = -1;
		String lastDay = null;
		boolean multiDay = events.size() > 1 && !Fmt.localDate(events.getFirst().at).equals(Fmt.localDate(events.getLast().at));
		for (int i = 0; i < events.size(); i++) {
			TimelineEvent e = events.get(i);
			ZonedDateTime z = Instant.ofEpochMilli(e.at).atZone(Fmt.zone());
			String day = Fmt.dayLabel(z.toLocalDate());
			if (!day.equals(lastDay) || z.getHour() != lastHour) {
				if (multiDay && !day.equals(lastDay)) {
					cy += 4;
					ui.text(day.toUpperCase(), x, cy, ui.text2);
					cy += ui.lineH() + 6;
				}
				lastDay = day;
				lastHour = z.getHour();
				ui.text(String.format("%02d:00", z.getHour()), x, cy + 2, ui.text3);
				ui.hline(x + 40, cy + 6, w - 40, ui.border);
				cy += ui.lineH() + 6;
			}
			int detailLines = e.detail == null ? 0 : Math.min(3, ui.wrap(e.detail, textW).size());
			int rowH = 8 + ui.lineH() + (detailLines > 0 ? detailLines * (ui.lineH() + 2) + 2 : 0) + 6;
			boolean visible = ui.visible(cy, rowH);
			if (visible) {
				String rid = id + "#e" + i;
				ui.hit(rid, x - 4, cy, w + 8, rowH);
				float hv = ui.hoverAnim(rid);
				if (hv > 0) {
					ui.roundRect(x - 4, cy, w + 8, rowH, Math.min(3, ui.th.radius), Ui.withAlpha(ui.hover, hv * 1.3f));
				}
				ui.textRight(Fmt.timeSeconds(e.at), x + timeW, cy + 6, ui.text3);
				ui.vline(railX + 4, cy, rowH, Ui.withAlpha(ui.border, 0.9f));
				int col = color(ui, e);
				ui.roundRect(railX - 2, cy + 3, 13, 13, Math.min(6, ui.th.radius + 2), Ui.argb(ui.th.surface, 1f));
				ui.icon(icon(e.type), railX, cy + 5, col);
				String title = e.title;
				if (e.type == TimelineEvent.Type.CHAT) {
					boolean open = expanded.contains(groupKey(e));
					ui.icon(open ? Icons.CHEVRON_DOWN : Icons.CHEVRON_RIGHT, textX, cy + 6, ui.text2);
					ui.textClip(title + (e.endAt > e.at ? " · " + Fmt.duration(e.endAt - e.at) : ""), textX + 12, cy + 6, textW - 12, ui.text);
				} else {
					ui.textClip(title, textX, cy + 6, textW, ui.text);
				}
				if (detailLines > 0) {
					List<OrderedText> lines = ui.wrap(e.detail, textW);
					for (int l = 0; l < detailLines; l++) {
						ui.text(lines.get(l), textX, cy + 8 + ui.lineH() + 2 + l * (ui.lineH() + 2), ui.text2);
					}
				}
				if (ui.clicked(rid)) {
					onClick(screen, e, currentSession);
				}
			}
			cy += rowH;
			if (e.type == TimelineEvent.Type.CHAT && expanded.contains(groupKey(e))) {
				cy += renderChatGroup(ui, screen, x, cy, w, textX, e, chat.get(e.sessionId));
			}
		}
		return cy - y;
	}

	private int renderChatGroup(Ui ui, SiqScreen screen, int x, int y, int w, int textX, TimelineEvent e, List<ChatRecord> chat) {
		int cy = y;
		int lh = ui.lineH() + 4;
		if (chat == null) {
			ui.text("Loading messages…", textX, cy + 2, ui.text3);
			return lh + 4;
		}
		String key = groupKey(e);
		int limit = shownCount.getOrDefault(key, 100);
		int to = Math.min(e.chatTo, Math.min(chat.size(), e.chatFrom + limit));
		int msgX = textX + 4;
		int msgW = x + w - msgX - 4;
		for (int i = e.chatFrom; i < to; i++) {
			ChatRecord r = chat.get(i);
			if (ui.visible(cy, lh)) {
				ui.vline(textX - 12 + 4, cy, lh, Ui.withAlpha(ui.border, 0.9f));
				String time = Fmt.time(r.at);
				ui.text(time, msgX, cy + 2, ui.text3);
				int tx = msgX + ui.textW(time) + 6;
				ui.textClip(r.text.replace('\n', ' '), tx, cy + 2, msgX + msgW - tx, r.isSystem() ? ui.text2 : ui.text);
			}
			cy += lh;
		}
		int linkY = cy + 2;
		int lx = msgX;
		if (to < e.chatTo && to < chat.size()) {
			String more = "Show " + Math.min(100, e.chatTo - to) + " more";
			if (ui.link(id + "#more" + key, more, lx, linkY, ui.accent)) {
				shownCount.put(key, limit + 100);
			}
			lx += ui.textW(more) + 14;
		}
		if (ui.link(id + "#openchat" + key, "Open in Chat History", lx, linkY, ui.accent)) {
			ChatPage page = new ChatPage(screen);
			page.presetSession(e.sessionId, chat.get(e.chatFrom).at - 1, chat.get(Math.min(e.chatTo, chat.size()) - 1).at + 1);
			screen.push(page);
		}
		return cy - y + ui.lineH() + 10;
	}

	private void onClick(SiqScreen screen, TimelineEvent e, String currentSession) {
		switch (e.type) {
			case CHAT -> {
				String k = groupKey(e);
				if (!expanded.remove(k)) {
					expanded.add(k);
				}
			}
			case SERVER_JOIN, SERVER_LEAVE -> {
				if (e.disconnectId != null && e.type == TimelineEvent.Type.SERVER_LEAVE) {
					screen.push(new DisconnectDetailPage(screen, e.sessionId, e.disconnectId));
				} else if (e.serverKey != null) {
					screen.push(new ServerDetailPage(screen, e.serverKey));
				}
			}
			case DISCONNECT, CONNECTION_FAILED, RECONNECTED -> {
				if (e.disconnectId != null) {
					screen.push(new DisconnectDetailPage(screen, e.sessionId, e.disconnectId));
				}
			}
			default -> {
				if (!e.sessionId.equals(currentSession)) {
					screen.push(new SessionDetailPage(screen, e.sessionId));
				}
			}
		}
	}

	public void collapseAll() {
		expanded.clear();
	}
}
