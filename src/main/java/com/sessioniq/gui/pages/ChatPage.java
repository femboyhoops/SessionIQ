package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.analytics.ChatSearch;
import com.sessioniq.analytics.DateRange;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.DateRangeControl;
import com.sessioniq.gui.ExportModal;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.DataActions;
import com.sessioniq.track.ServerIdentity;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatPage extends Page {
	private static final int PAGE = 150;

	private final DateRangeControl range = new DateRangeControl("chat#range", DateRange.Preset.ALL,
		List.of(DateRange.Preset.ALL, DateRange.Preset.TODAY, DateRange.Preset.LAST_7, DateRange.Preset.LAST_30, DateRange.Preset.LAST_90, DateRange.Preset.CUSTOM));
	private final Set<String> categories = new LinkedHashSet<>();
	private String serverKey;
	private String sessionId;
	private long sessionFrom = Long.MIN_VALUE;
	private long sessionTo = Long.MAX_VALUE;
	private boolean newestFirst = true;

	private final List<ChatRecord> results = new ArrayList<>();
	private ChatSearch search;
	private int token;
	private boolean loading;
	private boolean exhausted;
	private String error;
	private int damaged;
	private String criteriaKey;
	private long criteriaChangedAt;
	private boolean selectMode;
	private final Map<String, ChatRecord> selected = new LinkedHashMap<>();
	private int highlight = -1;

	private final List<Integer> rowTops = new ArrayList<>();
	private final Map<String, List<OrderedText>> wrapCache = new HashMap<>();
	private int layoutWidth = -1;
	private String layoutMode;
	private int contentHeight;

	private List<String> serverKeys = List.of();
	private int serverKeysRevision = -1;

	public ChatPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Chat History";
	}

	public void presetSession(String sessionId, long from, long to) {
		this.sessionId = sessionId;
		this.sessionFrom = from;
		this.sessionTo = to;
		this.newestFirst = from == Long.MIN_VALUE;
	}

	public void presetServer(String key) {
		this.serverKey = key;
	}

	@Override
	public void onShow() {
		criteriaKey = null;
	}

	private ChatSearch.Criteria criteria() {
		ChatSearch.Criteria c = new ChatSearch.Criteria();
		c.text = screen.ui.field("chat#q").text;
		c.sender = screen.ui.field("chat#sender").text;
		if (serverKey != null) {
			c.servers.add(serverKey);
		}
		DateRange r = range.range();
		ZoneId zone = Fmt.zone();
		c.fromMillis = r.startMillis(zone);
		c.toMillis = r.endMillis(zone);
		if (sessionId != null) {
			c.sessionId = sessionId;
			c.fromMillis = Math.max(c.fromMillis, sessionFrom);
			c.toMillis = Math.min(c.toMillis, sessionTo);
		}
		c.categories.addAll(categories);
		c.newestFirst = newestFirst;
		c.mergePorts = siq.config.get().mergePorts;
		return c;
	}

	private String keyOf(ChatSearch.Criteria c) {
		return c.text + "|" + c.sender + "|" + c.servers + "|" + c.fromMillis + "|" + c.toMillis + "|" + c.sessionId + "|" + c.categories + "|" + c.newestFirst
			+ "|" + c.mergePorts;
	}

	private void startSearch(ChatSearch.Criteria c) {
		int my = ++token;
		results.clear();
		rowTops.clear();
		contentHeight = 0;
		selected.clear();
		highlight = -1;
		loading = true;
		exhausted = false;
		error = null;
		damaged = 0;
		screen.ui.scrollTo("chat#list", 0);
		List<SessionRecord> snapshot = siq.sessions.snapshot();
		siq.chat.flush();
		siq.storage.submitIo(() -> null)
			.thenCompose(v -> siq.storage.submitQuery(() -> {
				ChatSearch s = new ChatSearch(siq.chat, snapshot, c);
				return new Object[]{s, s.next(PAGE)};
			}))
			.whenCompleteAsync((res, err) -> {
				if (my != token) {
					return;
				}
				loading = false;
				if (err != null) {
					error = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
					return;
				}
				search = (ChatSearch) res[0];
				accept((ChatSearch.Page) res[1]);
			}, SessionIQ.mainThread());
	}

	private void loadMore() {
		if (loading || exhausted || search == null) {
			return;
		}
		int my = token;
		loading = true;
		ChatSearch s = search;
		siq.storage.submitQuery(() -> s.next(PAGE)).whenCompleteAsync((page, err) -> {
			if (my != token) {
				return;
			}
			loading = false;
			if (err != null) {
				error = err.getMessage();
				return;
			}
			accept(page);
		}, SessionIQ.mainThread());
	}

	private void accept(ChatSearch.Page page) {
		results.addAll(page.items());
		exhausted = page.exhausted();
		damaged += page.damagedLines();
		if (!page.errors().isEmpty()) {
			error = page.errors().getFirst();
		}
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		int x = right;
		int ew = ui.buttonWidth("Export", Icons.EXPORT);
		x -= ew;
		if (ui.button("chat#export", x, y, ew, h, "Export", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			ChatSearch.Criteria current = criteria();
			ChatSearch.Criteria all = new ChatSearch.Criteria();
			all.mergePorts = current.mergePorts;
			screen.openModal(new ExportModal(screen, List.of(
				new ExportModal.Option("Messages matching the current filters", "Everything the current search, server, date, category and session filters match.",
					List.of(Format.TEXT, Format.CSV, Format.JSON), f -> siq.exports.chat(siq.sessions.snapshot(), current, f, ServerNames::name)),
				new ExportModal.Option("Entire chat archive", "Every recorded message from every session.",
					List.of(Format.TEXT, Format.CSV, Format.JSON), f -> siq.exports.chat(siq.sessions.snapshot(), all, f, ServerNames::name))
			)));
		}
		x -= 6;
		String label = selectMode ? "Done" : "Select";
		int sw = ui.buttonWidth(label, null);
		x -= sw;
		if (ui.button("chat#select", x, y, sw, h, label, selectMode ? Ui.Btn.PRIMARY : Ui.Btn.SECONDARY)) {
			selectMode = !selectMode;
			selected.clear();
		}
		x -= 6;
		boolean detailed = siq.config.get().chatDetailed;
		int vw = ui.segmentedWidth(new String[]{"Compact", "Detailed"});
		x -= vw;
		int v = ui.segmented("chat#view", x, y, new String[]{"Compact", "Detailed"}, detailed ? 1 : 0);
		if (v >= 0 && (v == 1) != detailed) {
			siq.config.get().chatDetailed = v == 1;
			siq.config.changed();
		}
		return x - 6;
	}

	private void ensureServerKeys() {
		int rev = siq.sessions.revision();
		if (rev == serverKeysRevision) {
			return;
		}
		serverKeysRevision = rev;
		boolean merge = siq.config.get().mergePorts;
		Map<String, Long> last = new HashMap<>();
		for (SessionRecord s : siq.sessions.sorted()) {
			for (VisitRecord v : s.visits) {
				if (v.chatCount > 0 || v.open) {
					last.merge(ServerIdentity.groupKey(v, merge), v.joinedAt, Math::max);
				}
			}
		}
		List<String> keys = new ArrayList<>(last.keySet());
		keys.sort((a, b) -> Long.compare(last.get(b), last.get(a)));
		serverKeys = keys;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		ensureServerKeys();
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad;
		int bh = ui.buttonH() + 2;

		boolean narrow = cw < 640;
		int g = ui.gap();
		int senderW = narrow ? (cw - g) / 2 : 120;
		int serverW = narrow ? (cw - 2 * g) / 3 : 130;
		int dateW = narrow ? (cw - 2 * g) / 3 : 112;
		int orderW = narrow ? cw - serverW - dateW - 2 * g : 100;
		int qW = narrow ? cw - senderW - g : cw - senderW - serverW - dateW - orderW - 4 * g;
		TextField q = ui.textField("chat#q", cx, cy, qW, bh, "Search messages…", Icons.SEARCH);
		TextField sender = ui.textField("chat#sender", cx + qW + g, cy, senderW, bh, "Player name", null);
		int fy = narrow ? cy + bh + g : cy;
		int fx = narrow ? cx : cx + qW + senderW + 2 * g;
		List<String> serverOptions = new ArrayList<>();
		serverOptions.add("All servers");
		int serverIndex = 0;
		for (int i = 0; i < serverKeys.size(); i++) {
			serverOptions.add(ServerNames.name(serverKeys.get(i)));
			if (serverKeys.get(i).equals(serverKey)) {
				serverIndex = i + 1;
			}
		}
		if (serverKey != null && serverIndex == 0) {
			serverOptions.add(ServerNames.name(serverKey));
			serverIndex = serverOptions.size() - 1;
		}
		int si = ui.dropdown("chat#server", fx, fy, serverW, bh, serverOptions, serverIndex);
		if (si != serverIndex) {
			serverKey = si == 0 ? null : si - 1 < serverKeys.size() ? serverKeys.get(si - 1) : serverKey;
		}
		range.render(ui, screen, fx + serverW + g, fy, dateW, bh);
		range.consumeChanged();
		int oi = ui.dropdown("chat#order", fx + serverW + dateW + 2 * g, fy, orderW, bh, List.of("Newest first", "Oldest first"), newestFirst ? 0 : 1);
		newestFirst = oi == 0;
		cy = fy + bh + g;

		int chipX = cx;
		int chipH = ui.lineH() + 7;
		if (chip(ui, "chat#cat#all", chipX, cy, chipH, "All", categories.isEmpty())) {
			categories.clear();
		}
		chipX += ui.textW("All") + 16 + 4;
		for (ChatCategory cat : ChatCategory.values()) {
			int cwChip = ui.textW(cat.label) + 16;
			if (chipX + cwChip > cx + cw) {
				chipX = cx;
				cy += chipH + 4;
			}
			String id = "chat#cat#" + cat.id;
			if (chip(ui, id, chipX, cy, chipH, cat.label, categories.contains(cat.id))) {
				if (!categories.remove(cat.id)) {
					categories.add(cat.id);
				}
			}
			chipX += cwChip + 4;
		}
		cy += chipH + g;
		if (sessionId != null) {
			SessionRecord s = siq.sessions.get(sessionId);
			String label = "Session " + (s == null ? sessionId : Fmt.dateTime(s.startedAt))
				+ (sessionFrom != Long.MIN_VALUE ? " · " + Fmt.time(sessionFrom) + "–" + Fmt.time(sessionTo) : "");
			int lw = ui.textW(label) + 30;
			ui.roundRect(cx, cy, lw, chipH, Math.min(ui.th.radius, chipH / 2), ui.selected);
			ui.text(label, cx + 8, cy + 4, ui.text);
			if (ui.iconButton("chat#clearsession", cx + lw - 16, cy + (chipH - 13) / 2, 13, Icons.CLOSE, "Remove session filter")) {
				sessionId = null;
				sessionFrom = Long.MIN_VALUE;
				sessionTo = Long.MAX_VALUE;
			}
			cy += chipH + g;
		}

		q.consumeChanged();
		sender.consumeChanged();
		if (!siq.isReady()) {
			ui.loading(cx, cy, cw, h - (cy - y), "Loading history…");
			return;
		}
		ChatSearch.Criteria c = criteria();
		String key = keyOf(c);
		long now = System.currentTimeMillis();
		if (!key.equals(criteriaKey)) {
			if (pendingKey == null || !pendingKey.equals(key)) {
				pendingKey = key;
				criteriaChangedAt = now;
			}
			boolean typing = ui.isFocused("chat#q") || ui.isFocused("chat#sender");
			if (criteriaKey == null || !typing || now - criteriaChangedAt > 250 || q.consumeSubmitted() || sender.consumeSubmitted()) {
				criteriaKey = key;
				pendingKey = null;
				startSearch(c);
			}
		}

		String status;
		if (loading && results.isEmpty()) {
			status = "Searching…";
		} else {
			status = Fmt.count(results.size()) + (exhausted ? "" : "+") + " message" + (results.size() == 1 ? "" : "s")
				+ (damaged > 0 ? " · " + damaged + " damaged line(s) skipped" : "");
		}
		ui.text(status, cx, cy + 2, damaged > 0 ? ui.warning : ui.text3);
		if (selectMode) {
			String sel = selected.size() + " selected";
			int bx = cx + cw;
			int dw = ui.buttonWidth("Delete", Icons.TRASH);
			bx -= dw;
			if (ui.button("chat#delsel", bx, cy - 3, dw, ui.buttonH(), "Delete", Icons.TRASH, Ui.Btn.DANGER, !selected.isEmpty())) {
				confirmDelete(new ArrayList<>(selected.values()));
			}
			int cpw = ui.buttonWidth("Copy", Icons.COPY);
			bx -= cpw + 6;
			if (ui.button("chat#copysel", bx, cy - 3, cpw, ui.buttonH(), "Copy", Icons.COPY, Ui.Btn.SECONDARY, !selected.isEmpty())) {
				copy(new ArrayList<>(selected.values()));
			}
			int aw = ui.buttonWidth("Select all loaded", null);
			bx -= aw + 6;
			if (ui.button("chat#selall", bx, cy - 3, aw, ui.buttonH(), "Select all loaded", Ui.Btn.GHOST)) {
				for (ChatRecord r : results) {
					selected.put(r.id, r);
				}
			}
			ui.textRight(sel, bx - 8, cy + 2, ui.text2);
		}
		cy += ui.lineH() + 8;

		int listY = cy;
		int listH = y + h - listY - pad / 2;
		ui.card(cx, listY, cw, listH);
		if (error != null && results.isEmpty()) {
			if (ui.emptyState("chat#error", cx, listY, cw, listH, Icons.WARNING, "Search failed", error, "Retry")) {
				criteriaKey = null;
			}
			return;
		}
		if (results.isEmpty()) {
			if (loading) {
				ui.loading(cx, listY, cw, listH, "Searching the archive…");
			} else {
				boolean anyChat = siq.sessions.sorted().stream().anyMatch(s -> s.chatCount > 0);
				boolean filtered = !c.text.isBlank() || !c.sender.isBlank() || !c.servers.isEmpty() || !c.categories.isEmpty() || c.sessionId != null
					|| !range.range().isAll();
				String title = !anyChat ? "No chat recorded yet" : "No matching messages";
				String msg = !anyChat
					? (siq.config.get().recordChat ? "Messages you receive while connected to a server or in a world are archived here." : "Chat recording is turned off in Settings › Recording.")
					: "No archived message matches these filters.";
				if (ui.emptyState("chat#empty", cx, listY, cw, listH, Icons.CHAT, title, msg, filtered && anyChat ? "Clear filters" : null)) {
					clearFilters(ui);
				}
			}
			return;
		}
		renderList(ui, cx, listY, cw, listH);
	}

	private String pendingKey;

	private boolean chip(Ui ui, String id, int x, int y, int h, String label, boolean on) {
		int w = ui.textW(label) + 16;
		ui.hit(id, x, y, w, h);
		float hv = ui.hoverAnim(id);
		ui.roundRect(x, y, w, h, Math.min(ui.th.radius, h / 2), on ? ui.selected : Ui.withAlpha(ui.hover, 0.8f + hv));
		if (on) {
			ui.roundBorder(x, y, w, h, Math.min(ui.th.radius, h / 2), Ui.withAlpha(ui.accent, 0.6f));
		}
		ui.text(label, x + 8, y + 4, on ? ui.text : ui.text2);
		return ui.clicked(id);
	}

	private void clearFilters(Ui ui) {
		ui.field("chat#q").set("");
		ui.field("chat#sender").set("");
		serverKey = null;
		sessionId = null;
		sessionFrom = Long.MIN_VALUE;
		sessionTo = Long.MAX_VALUE;
		categories.clear();
		range.set(DateRange.of(DateRange.Preset.ALL, Fmt.zone()));
	}

	private Text styledText(Ui ui, ChatRecord r) {
		MutableText t = Text.empty();
		if (r.format != null && siq.config.get().recordChatFormatting) {
			int pos = 0;
			for (int[] run : r.format) {
				int end = Math.min(r.text.length(), pos + Math.max(0, run[0]));
				if (end <= pos) {
					continue;
				}
				Style st = Style.EMPTY.withColor(readable(ui, run[1] < 0 ? (r.isSystem() ? ui.th.text2 : ui.th.text) : run[1]))
					.withBold((run[2] & 1) != 0).withItalic((run[2] & 2) != 0).withUnderline((run[2] & 4) != 0).withStrikethrough((run[2] & 8) != 0);
				t.append(Text.literal(r.text.substring(pos, end)).setStyle(st));
				pos = end;
			}
			if (pos < r.text.length()) {
				t.append(Ui.styled(r.text.substring(pos), r.isSystem() ? ui.th.text2 : ui.th.text));
			}
		} else {
			t.append(Ui.styled(r.text, r.isSystem() ? ui.th.text2 : ui.th.text));
		}
		return t;
	}

	private static int readable(Ui ui, int rgb) {
		double lum = Ui.luminance(rgb);
		if (ui.th.dark && lum < 0.18) {
			return Ui.mix(rgb, 0xFFFFFF, 0.45f);
		}
		if (!ui.th.dark && lum > 0.62) {
			return Ui.mix(rgb, 0x000000, 0.45f);
		}
		return rgb;
	}

	private void layout(Ui ui, int textW, boolean detailed) {
		String mode = detailed + "|" + textW + "|" + ui.fontScale + "|" + ui.th.name;
		if (!mode.equals(layoutMode)) {
			layoutMode = mode;
			wrapCache.clear();
			rowTops.clear();
			contentHeight = 0;
		}
		int lh = ui.lineH() + 2;
		while (rowTops.size() < results.size()) {
			ChatRecord r = results.get(rowTops.size());
			List<OrderedText> lines = ui.wrap(styledText(ui, r), textW);
			wrapCache.put(r.id, lines);
			int h = lines.size() * lh + (detailed ? lh + 10 : 6);
			rowTops.add(contentHeight);
			contentHeight += h;
		}
	}

	private int rowHeight(int i) {
		return (i + 1 < rowTops.size() ? rowTops.get(i + 1) : contentHeight) - rowTops.get(i);
	}

	private void renderList(Ui ui, int x, int y, int w, int h) {
		boolean detailed = siq.config.get().chatDetailed;
		int leftPad = selectMode ? 26 : 10;
		int timeW = detailed ? 0 : ui.textW("00:00:00 PM") + 10;
		int textX = x + leftPad + timeW;
		int actionsW = 34;
		int textW = x + w - textX - actionsW - 6;
		if (layoutWidth != textW) {
			layoutWidth = textW;
		}
		layout(ui, textW, detailed);
		String sid = "chat#list";
		int off = ui.beginScroll(sid, x, y + 1, w, h - 2);
		int lh = ui.lineH() + 2;
		int lo = 0;
		int hi = rowTops.size() - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (rowTops.get(mid) <= off) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		for (int i = lo; i < results.size(); i++) {
			int top = rowTops.get(i);
			if (top - off > h) {
				break;
			}
			ChatRecord r = results.get(i);
			int ry = y + 1 + top - off;
			int rh = rowHeight(i);
			String rid = "chat#row" + r.id;
			ui.hit(rid, x + 1, ry, w - 2, rh);
			float hv = ui.hoverAnim(rid);
			boolean sel = selected.containsKey(r.id);
			if (sel || i == highlight) {
				ui.fill(x + 1, ry, w - 2, rh, ui.selected);
			}
			if (hv > 0) {
				ui.fill(x + 1, ry, w - 2, rh, Ui.withAlpha(ui.hover, hv * 1.3f));
			}
			if (r.isSystem()) {
				ui.fill(x + leftPad - 6, ry + 3, 2, rh - 6, Ui.withAlpha(ui.text3, 0.7f));
			}
			if (selectMode) {
				String cb = "chat#cb" + r.id;
				if (ui.checkbox(cb, x + 9, ry + 4, sel, null)) {
					toggle(r);
				}
			}
			List<OrderedText> lines = wrapCache.get(r.id);
			int ty = ry + 3;
			if (detailed) {
				String header = Fmt.dateTimeSeconds(r.at) + "  ·  " + ServerNames.name(r.serverKey);
				ui.textClip(header, textX, ty, textW - 90, ui.text3);
				ChatCategory cat = ChatCategory.parse(r.category);
				ui.badge(textX + Math.min(textW - 90, ui.textW(header)) + 8, ty - 2, cat.label, r.isSystem() ? ui.text2 : ui.accent);
				ty += lh + 4;
			} else {
				ui.text(Fmt.timeSeconds(r.at), x + leftPad, ty, ui.text3);
			}
			for (int l = 0; l < lines.size(); l++) {
				ui.text(lines.get(l), textX, ty + l * lh, ui.text);
			}
			if (hv > 0.05f && !selectMode) {
				if (ui.iconButton("chat#copy" + r.id, x + w - actionsW - 2, ry + 2, 15, Icons.COPY, "Copy message")) {
					copy(List.of(r));
				}
				if (ui.iconButton("chat#open" + r.id, x + w - actionsW + 15, ry + 2, 15, Icons.CHEVRON_RIGHT, "Details, session and server")) {
					screen.push(new ChatMessagePage(screen, r));
				}
			}
			if (ui.clicked(rid)) {
				highlight = i;
				if (selectMode) {
					toggle(r);
				} else if (ui.wasDoubleClick()) {
					screen.push(new ChatMessagePage(screen, r));
				}
			}
			ui.tooltip(rid, selectMode ? null : Fmt.dateTimeSeconds(r.at) + " · " + ServerNames.name(r.serverKey) + "\nDouble-click for details");
		}
		int total = contentHeight + (exhausted ? 8 : 30);
		if (!exhausted) {
			int fy = y + 1 + contentHeight - off + 8;
			if (loading) {
				ui.textCenter("Loading more…", x + w / 2, fy, ui.text3);
			} else if (ui.link("chat#more", "Load more", x + w / 2 - ui.textW("Load more") / 2, fy, ui.accent)) {
				loadMore();
			}
		}
		ui.endScroll(sid, total);
		if (ui.nearBottom(sid, 300)) {
			loadMore();
		}
	}

	private void toggle(ChatRecord r) {
		if (selected.remove(r.id) == null) {
			selected.put(r.id, r);
		}
	}

	private void copy(List<ChatRecord> records) {
		StringBuilder sb = new StringBuilder();
		List<ChatRecord> ordered = new ArrayList<>(records);
		ordered.sort((a, b) -> Long.compare(a.at, b.at));
		for (ChatRecord r : ordered) {
			if (records.size() > 1) {
				sb.append('[').append(Fmt.dateTimeSeconds(r.at)).append("] ");
			}
			sb.append(r.text);
			if (records.size() > 1) {
				sb.append('\n');
			}
		}
		screen.ui.copyToClipboard(sb.toString());
		screen.toast(records.size() == 1 ? "Message copied" : records.size() + " messages copied", SiqScreen.TOAST_INFO);
	}

	private void confirmDelete(List<ChatRecord> records) {
		screen.confirm("Delete " + Fmt.plural(records.size(), "message", "messages") + "?",
			"The selected messages are permanently removed from the archive and from session and server counts. Other messages are not affected. This cannot be undone (backups made earlier still contain them).",
			"Delete", true, () -> DataActions.deleteChat(siq, records).whenCompleteAsync((n, err) -> {
				if (err != null) {
					screen.toast("Could not delete messages: " + err.getMessage(), SiqScreen.TOAST_ERROR);
					return;
				}
				Set<String> ids = new java.util.HashSet<>();
				records.forEach(r -> ids.add(r.id));
				results.removeIf(r -> ids.contains(r.id));
				selected.clear();
				layoutMode = null;
				screen.toast(Fmt.plural(n, "message", "messages") + " deleted", SiqScreen.TOAST_SUCCESS);
			}, SessionIQ.mainThread()));
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (input.hasCtrlOrCmd() && input.key() == GLFW.GLFW_KEY_F) {
			ui.focus("chat#q");
			return true;
		}
		if (results.isEmpty()) {
			return false;
		}
		if (input.isCopy() && highlight >= 0 && highlight < results.size()) {
			copy(selectMode && !selected.isEmpty() ? new ArrayList<>(selected.values()) : List.of(results.get(highlight)));
			return true;
		}
		if (selectMode && input.isSelectAll()) {
			for (ChatRecord r : results) {
				selected.put(r.id, r);
			}
			return true;
		}
		switch (input.key()) {
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
				highlight = Math.max(0, Math.min(results.size() - 1, highlight + (input.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
				if (highlight < rowTops.size()) {
					int top = rowTops.get(highlight);
					int off = ui.scrollOffset("chat#list");
					int view = ui.scrollView("chat#list");
					if (top < off) {
						ui.scrollTo("chat#list", top);
					} else if (top + rowHeight(highlight) > off + view) {
						ui.scrollTo("chat#list", top + rowHeight(highlight) - view);
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (highlight >= 0) {
					screen.push(new ChatMessagePage(screen, results.get(highlight)));
					return true;
				}
				return false;
			}
			case GLFW.GLFW_KEY_SPACE -> {
				if (selectMode && highlight >= 0) {
					toggle(results.get(highlight));
					return true;
				}
				return false;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (selectMode && !selected.isEmpty()) {
					confirmDelete(new ArrayList<>(selected.values()));
					return true;
				}
				return false;
			}
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				ui.scrollBy("chat#list", ui.scrollView("chat#list") - 20);
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				ui.scrollBy("chat#list", -ui.scrollView("chat#list") + 20);
				return true;
			}
			default -> {
				return false;
			}
		}
	}
}
