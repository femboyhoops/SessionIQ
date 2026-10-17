package com.sessioniq.gui.pages;

import com.sessioniq.analytics.TimelineBuilder;
import com.sessioniq.analytics.TimelineEvent;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.PromptModal;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.track.ServerIdentity;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TimelinePage extends Page {
	private enum Filter {
		SESSIONS("Sessions", EnumSet.of(TimelineEvent.Type.SESSION_START, TimelineEvent.Type.SESSION_END, TimelineEvent.Type.SESSION_RECOVERED)),
		SERVERS("Joins & leaves", EnumSet.of(TimelineEvent.Type.SERVER_JOIN, TimelineEvent.Type.SERVER_LEAVE)),
		DISCONNECTS("Disconnects", EnumSet.of(TimelineEvent.Type.DISCONNECT, TimelineEvent.Type.CONNECTION_FAILED, TimelineEvent.Type.RECONNECTED)),
		CHAT("Chat", EnumSet.of(TimelineEvent.Type.CHAT)),
		DEATHS("Deaths", EnumSet.of(TimelineEvent.Type.DEATH));

		final String label;
		final Set<TimelineEvent.Type> types;

		Filter(String label, Set<TimelineEvent.Type> types) {
			this.label = label;
			this.types = types;
		}
	}

	private final boolean detail;
	private LocalDate day;
	private final Set<Filter> hidden = new LinkedHashSet<>();
	private String serverKey;
	private final TimelineView view = new TimelineView("tl#view");
	private final Async<Map<String, List<ChatRecord>>> chat = new Async<>();
	private List<TimelineEvent> events = List.of();
	private String eventsKey;

	public TimelinePage(SiqScreen screen) {
		super(screen);
		this.detail = false;
		this.day = LocalDate.now(Fmt.zone());
	}

	public TimelinePage(SiqScreen screen, LocalDate day) {
		super(screen);
		this.detail = true;
		this.day = day;
	}

	@Override
	public String title() {
		return detail ? "Timeline · " + Fmt.dayLabel(day) : "Timeline";
	}

	@Override
	public String crumb() {
		return detail ? Fmt.date(day) : "Timeline";
	}

	private List<SessionRecord> sessionsOfDay(ZoneId zone) {
		long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
		long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
		List<SessionRecord> out = new ArrayList<>();
		for (SessionRecord s : siq.sessions.sorted()) {
			long sEnd = s.isActive() ? System.currentTimeMillis() : s.endEstimate();
			if (sEnd >= start && s.startedAt < end) {
				out.add(s);
			}
		}
		return out;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int bh = ui.buttonH() + 2;
		ZoneId zone = Fmt.zone();

		if (ui.iconButton("tl#prev", cx, cy + (bh - 18) / 2, 18, Icons.CHEVRON_LEFT, "Previous day")) {
			day = day.minusDays(1);
		}
		String label = Fmt.dayLabel(day);
		int lw = Math.max(150, ui.textW(label) + 30);
		String pid = "tl#pick";
		ui.hit(pid, cx + 22, cy, lw, bh);
		float hv = ui.hoverAnim(pid);
		ui.panel(cx + 22, cy, lw, bh, Ui.mixArgb(ui.surface2, ui.text, 0.03f * hv), ui.border, Math.min(ui.th.radius, bh / 2));
		ui.icon(Icons.CALENDAR, cx + 30, cy + (bh - 9) / 2, ui.text2);
		ui.textMid(label, cx + 44, cy, bh, ui.text);
		if (ui.clicked(pid)) {
			screen.openModal(new PromptModal("Go to date", "Date (YYYY-MM-DD)", day.toString(), "Go", 10, v -> {
				try {
					LocalDate.parse(v.trim());
					return null;
				} catch (Exception e) {
					return "Use the format YYYY-MM-DD";
				}
			}, v -> day = LocalDate.parse(v.trim()), ui));
		}
		boolean isToday = day.equals(LocalDate.now(zone));
		if (ui.iconButton("tl#next", cx + 26 + lw, cy + (bh - 18) / 2, 18, Icons.CHEVRON_RIGHT, "Next day")) {
			day = day.plusDays(1);
		}
		int bx = cx + 50 + lw;
		if (!isToday && ui.button("tl#today", bx, cy, 54, bh, "Today", Ui.Btn.SECONDARY)) {
			day = LocalDate.now(zone);
		}
		int searchW = Math.min(180, Math.max(100, cw - (bx - cx) - 260));
		int sx = cx + cw - searchW;
		TextField search = ui.textField("tl#search", sx, cy, searchW, bh, "Search events…", Icons.SEARCH);
		search.consumeChanged();
		List<SessionRecord> daySessions = siq.isReady() ? sessionsOfDay(zone) : List.of();
		List<String> keys = new ArrayList<>();
		boolean merge = siq.config.get().mergePorts;
		for (SessionRecord s : daySessions) {
			s.visits.forEach(v -> {
				String k = ServerIdentity.groupKey(v, merge);
				if (!keys.contains(k)) {
					keys.add(k);
				}
			});
		}
		List<String> options = new ArrayList<>();
		options.add("All servers");
		int sel = 0;
		for (int i = 0; i < keys.size(); i++) {
			options.add(ServerNames.name(keys.get(i)));
			if (keys.get(i).equals(serverKey)) {
				sel = i + 1;
			}
		}
		int ddw = 120;
		if (sx - ddw - ui.gap() > bx + 60) {
			int s = ui.dropdown("tl#server", sx - ddw - ui.gap(), cy, ddw, bh, options, sel);
			if (s != sel) {
				serverKey = s == 0 ? null : keys.get(s - 1);
			}
		}
		cy += bh + ui.gap();

		int chipX = cx;
		int chipH = ui.lineH() + 7;
		int chipLimit = cx + cw - ui.textW("Collapse chat") - 12;
		for (Filter f : Filter.values()) {
			int wChip = ui.textW(f.label) + 16;
			if (chipX + wChip > chipLimit && chipX > cx) {
				chipX = cx;
				cy += chipH + 4;
			}
			String id = "tl#f" + f.name();
			boolean on = !hidden.contains(f);
			ui.hit(id, chipX, cy, wChip, chipH);
			float chv = ui.hoverAnim(id);
			ui.roundRect(chipX, cy, wChip, chipH, Math.min(ui.th.radius, chipH / 2), on ? ui.selected : Ui.withAlpha(ui.hover, 0.8f + chv));
			ui.text(f.label, chipX + 8, cy + 4, on ? ui.text : ui.text3);
			if (ui.clicked(id)) {
				if (!hidden.remove(f)) {
					hidden.add(f);
				}
			}
			chipX += wChip + 4;
		}
		if (ui.link("tl#collapse", "Collapse chat", cx + cw - ui.textW("Collapse chat"), cy + 4, ui.accent)) {
			view.collapseAll();
		}
		cy += chipH + ui.gap();

		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}

		List<String> ids = daySessions.stream().map(s -> s.id).toList();
		long chatTotal = daySessions.stream().mapToLong(s -> s.chatCount).sum();
		siq.chat.flush();
		chat.ensure(day + "|" + ids + "|" + chatTotal, () -> siq.storage.submitIo(() -> null).thenCompose(v -> siq.storage.submitQuery(() -> {
			Map<String, List<ChatRecord>> out = new HashMap<>();
			for (SessionRecord s : daySessions) {
				if (s.chatCount <= SessionDetailPage.INLINE_CHAT_LIMIT) {
					List<ChatRecord> list = siq.chat.readSession(s.id);
					list.sort((a, b) -> Long.compare(a.at, b.at));
					out.put(s.id, list);
				}
			}
			return out;
		})));
		Map<String, List<ChatRecord>> chatMap = chat.get() == null ? Map.of() : chat.get();
		String key = day + "|" + siq.sessions.revision() + "|" + System.identityHashCode(chatMap) + "|" + hidden + "|" + serverKey + "|" + search.text + "|" + zone + "|"
			+ (chat.get() == null);
		if (!key.equals(eventsKey)) {
			eventsKey = key;
			events = buildEvents(daySessions, chatMap, zone, search.text, merge);
		}

		String sid = "tl#scroll";
		int listY = cy;
		int listH = y + h - listY;
		if (events.isEmpty()) {
			boolean filtered = !hidden.isEmpty() || serverKey != null || !search.text.isBlank();
			ui.emptyState("tl#empty", cx, listY, cw, Math.min(listH, 220), Icons.TIMELINE, filtered ? "No matching events" : "Nothing recorded on this day",
				filtered ? "Try showing more event types or clearing the search." : "Pick another day with the arrows or the date button.", null);
			return;
		}
		int off = ui.beginScroll(sid, x, listY, w, listH);
		int used = view.render(ui, screen, cx, listY + 4 - off, cw, events, chatMap, null);
		ui.endScroll(sid, used + pad + 8);
	}

	private List<TimelineEvent> buildEvents(List<SessionRecord> sessions, Map<String, List<ChatRecord>> chatMap, ZoneId zone, String query, boolean merge) {
		long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
		long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
		Set<TimelineEvent.Type> hiddenTypes = EnumSet.noneOf(TimelineEvent.Type.class);
		for (Filter f : hidden) {
			hiddenTypes.addAll(f.types);
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<TimelineEvent> out = new ArrayList<>();
		for (SessionRecord s : sessions) {
			for (TimelineEvent e : TimelineBuilder.build(s, chatMap.get(s.id), Fmt::duration, ServerNames::name)) {
				if (e.at < start || e.at >= end || hiddenTypes.contains(e.type)) {
					continue;
				}
				if (serverKey != null && (e.serverKey == null || !serverKey.equals(ServerIdentity.groupKey(e.serverKey, merge)))) {
					continue;
				}
				if (!q.isEmpty() && !(e.title + " " + (e.detail == null ? "" : e.detail)).toLowerCase(Locale.ROOT).contains(q)) {
					continue;
				}
				out.add(e);
			}
		}
		out.sort((a, b) -> Long.compare(a.at, b.at));
		return out;
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (input.key() == GLFW.GLFW_KEY_LEFT && !input.hasAlt()) {
			day = day.minusDays(1);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_RIGHT) {
			day = day.plusDays(1);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_T) {
			day = LocalDate.now(Fmt.zone());
			return true;
		}
		return false;
	}
}
