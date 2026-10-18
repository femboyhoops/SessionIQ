package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.SessionIQClient;
import com.sessioniq.gui.pages.AdminPage;
import com.sessioniq.gui.pages.ChatPage;
import com.sessioniq.gui.pages.DisconnectsPage;
import com.sessioniq.gui.pages.FriendsPage;
import com.sessioniq.gui.pages.GlobalPage;
import com.sessioniq.gui.pages.SuggestionsPage;
import com.sessioniq.gui.pages.OverviewPage;
import com.sessioniq.gui.pages.ServersPage;
import com.sessioniq.gui.pages.SessionsPage;
import com.sessioniq.gui.pages.SettingsPage;
import com.sessioniq.gui.pages.StatisticsPage;
import com.sessioniq.gui.pages.TimelinePage;
import com.sessioniq.storage.StorageService;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

public final class SiqScreen extends Screen {
	public record NavItem(String key, String label, Icons icon, Function<SiqScreen, Page> factory) {
	}

	public static final List<NavItem> NAV = List.of(
		new NavItem("overview", "Overview", Icons.OVERVIEW, OverviewPage::new),
		new NavItem("sessions", "Sessions", Icons.SESSIONS, SessionsPage::new),
		new NavItem("servers", "Servers", Icons.SERVERS, ServersPage::new),
		new NavItem("chat", "Chat History", Icons.CHAT, ChatPage::new),
		new NavItem("disconnects", "Disconnects", Icons.DISCONNECTS, DisconnectsPage::new),
		new NavItem("timeline", "Timeline", Icons.TIMELINE, TimelinePage::new),
		new NavItem("statistics", "Statistics", Icons.STATISTICS, StatisticsPage::new),
		new NavItem("global", "Global", Icons.GLOBE, GlobalPage::new),
		new NavItem("friends", "Friends", Icons.FRIENDS, FriendsPage::new),
		new NavItem("suggestions", "Suggestions", Icons.BULB, SuggestionsPage::new),
		new NavItem("admin", "Admin", Icons.SHIELD, AdminPage::new),
		new NavItem("settings", "Settings", Icons.SETTINGS, SettingsPage::new)
	);
	private static final int FIRST_ONLINE = 7;

	public static final Identifier LOGO = Identifier.of("sessioniq", "icon.png");
	public static final int LOGO_SIZE = 128;

	private final Screen parent;
	private final SessionIQ siq = SessionIQ.get();
	public final Ui ui = new Ui();
	private final Page[] roots = new Page[NAV.size()];
	private int section;
	private final Deque<Page> stack = new ArrayDeque<>();
	private final List<Modal> modals = new ArrayList<>();
	private final List<Toast> toasts = new ArrayList<>();
	private long pageShownAt;
	private long lastLiveUpdate;

	private record Toast(String text, int kind, long at, String action, Runnable onAction) {
	}

	public SiqScreen(Screen parent) {
		super(Text.literal("SessionIQ"));
		this.parent = parent;
		int start = 0;
		for (int i = 0; i < NAV.size(); i++) {
			if (NAV.get(i).key().equals(siq.config.get().defaultPage) && showInSidebar(NAV.get(i))) {
				start = i;
			}
		}
		selectSection(start);
		if (siq.online.takeNoticeTurn()) {
			openModal(new OnlineNoticeModal(this));
		}
	}

	/** The Admin page exists only for the developer's account; everyone else never sees it listed. */
	public boolean showInSidebar(NavItem item) {
		return !"admin".equals(item.key()) || siq.online.isAdmin();
	}

	public void selectSection(int index) {
		section = index;
		if (roots[index] == null) {
			roots[index] = NAV.get(index).factory().apply(this);
		}
		stack.clear();
		stack.push(roots[index]);
		ui.blur();
		ui.closePopup();
		shown();
	}

	public void selectSection(String key) {
		for (int i = 0; i < NAV.size(); i++) {
			if (NAV.get(i).key().equals(key)) {
				selectSection(i);
				return;
			}
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Page> T root(String key, Class<T> type) {
		for (int i = 0; i < NAV.size(); i++) {
			if (NAV.get(i).key().equals(key)) {
				if (roots[i] == null) {
					roots[i] = NAV.get(i).factory().apply(this);
				}
				return (T) roots[i];
			}
		}
		throw new IllegalArgumentException(key);
	}

	public void push(Page page) {
		stack.push(page);
		ui.blur();
		ui.closePopup();
		shown();
	}

	public void back() {
		if (stack.size() > 1) {
			stack.pop();
			ui.blur();
			shown();
		}
	}

	private void shown() {
		pageShownAt = System.currentTimeMillis();
		ui.resetAnim("page", 0f);
		stack.peek().onShow();
	}

	public Page current() {
		return stack.peek();
	}

	public void openModal(Modal modal) {
		ui.blur();
		ui.closePopup();
		modals.add(modal);
	}

	public boolean hasModal() {
		return !modals.isEmpty();
	}

	public void confirm(String title, String message, String confirmLabel, boolean danger, Runnable onConfirm) {
		openModal(new ConfirmModal(title, message, confirmLabel, danger, onConfirm));
	}

	public static final int TOAST_INFO = 0;
	public static final int TOAST_SUCCESS = 1;
	public static final int TOAST_ERROR = 2;

	public void toast(String text, int kind) {
		toast(text, kind, null, null);
	}

	public void toast(String text, int kind, String action, Runnable onAction) {
		toasts.add(new Toast(text, kind, System.currentTimeMillis(), action, onAction));
		while (toasts.size() > 4) {
			toasts.remove(0);
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		if (client != null && client.world != null && siq.themes.active().backdropOpacity < 0.999f) {
			applyBlur(context);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		long now = System.currentTimeMillis();
		if (now - lastLiveUpdate > 1000) {
			lastLiveUpdate = now;
			siq.tracker.updateLive();
		}
		ui.begin(context, width, height, mouseX, mouseY);
		int w = ui.width;
		int h = ui.height;
		ui.fill(0, 0, w, h, ui.bg);

		boolean collapsed = w < 560;
		int sideW = collapsed ? 34 : 136;
		renderSidebar(sideW, h, collapsed);

		int cx = sideW;
		int cw = w - sideW;
		int topH = 34;
		Page page = stack.peek();
		renderTopBar(page, cx, 0, cw, topH);

		float t = ui.anim("page", 1f, 12f);
		ui.alpha = 0.35f + 0.65f * t;
		int slide = Math.round((1f - t) * 6);
		ui.pushClip(cx, topH, cw, h - topH);
		page.render(ui, cx, topH + slide, cw, h - topH);
		ui.popClip();
		ui.alpha = 1f;

		renderStorageBanner(cx, h, cw);
		renderModals(w, h);
		renderToasts(w, h);
		ui.end();
	}

	private void renderSidebar(int sideW, int h, boolean collapsed) {
		ui.fill(0, 0, sideW, h, ui.bg2);
		ui.vline(sideW - 1, 0, h, ui.border);
		int y = 10;
		if (collapsed) {
			ui.image(LOGO, 9, y, 16, 16, LOGO_SIZE, LOGO_SIZE);
		} else {
			ui.image(LOGO, 12, y, 16, 16, LOGO_SIZE, LOGO_SIZE);
			ui.text("SessionIQ", 34, y + 4, ui.text);
		}
		y += 30;
		int shown = 0;
		for (NavItem item : NAV) {
			if (showInSidebar(item)) {
				shown++;
			}
		}
		int footer = collapsed ? 26 : 40;
		int room = h - y - footer - 8 - 12;
		int itemH = Math.max(14, Math.min(ui.compact ? 20 : 24, room / Math.max(1, shown) - 2));
		for (int i = 0; i < NAV.size(); i++) {
			NavItem item = NAV.get(i);
			if (!showInSidebar(item)) {
				continue;
			}
			if (i == NAV.size() - 1 || i == FIRST_ONLINE) {
				y += 6;
				ui.hline(10, y - 4, sideW - 20, ui.border);
			}
			String id = "nav#" + item.key();
			int ix = 6;
			int iw = sideW - 12;
			ui.hit(id, ix, y, iw, itemH);
			float hv = ui.hoverAnim(id);
			boolean sel = i == section;
			float s = ui.anim(id + "#sel", sel ? 1f : 0f, 16f);
			if (s > 0) {
				ui.roundRect(ix, y, iw, itemH, Math.min(ui.th.radius, 4), Ui.withAlpha(ui.selected, s));
				ui.fill(ix, y + 5, 2, itemH - 10, Ui.withAlpha(ui.accent, s));
			}
			if (hv > 0 && !sel) {
				ui.roundRect(ix, y, iw, itemH, Math.min(ui.th.radius, 4), Ui.withAlpha(ui.hover, hv * 1.4f));
			}
			int color = sel ? ui.text : Ui.mixArgb(ui.text2, ui.text, hv);
			ui.icon(item.icon(), ix + (collapsed ? (iw - 9) / 2 : 8), y + (itemH - 9) / 2, sel ? ui.accent : color);
			int badge = "friends".equals(item.key()) ? siq.online.unreadTotal() + siq.online.pendingRequests() : 0;
			if (!collapsed) {
				ui.textMid(item.label(), ix + 24, y, itemH, color);
				if (badge > 0) {
					String b = badge > 99 ? "99+" : String.valueOf(badge);
					ui.badge(ix + iw - ui.badgeW(b) - 4, y + (itemH - ui.lineH() - 3) / 2, b, ui.accent);
				}
			} else {
				ui.tooltip(id, item.label() + (badge > 0 ? " (" + badge + " new)" : ""));
				if (badge > 0) {
					ui.dot(ix + iw - 5, y + 5, 2, ui.accent);
				}
			}
			if (ui.clicked(id)) {
				selectSection(i);
			}
			y += itemH + 2;
		}

		int fy = h - (collapsed ? 26 : 40);
		if (fy < y + 8) {
			return;
		}
		boolean rec = siq.tracker.isRecording();
		boolean connected = siq.tracker.visit() != null;
		int dotColor = !rec ? ui.text3 : connected ? ui.success : ui.accent;
		float pulse = rec ? 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 700.0)) : 1f;
		if (collapsed) {
			ui.hit("status", 6, fy, sideW - 12, 20);
			ui.dot(sideW / 2, fy + 10, 3, Ui.withAlpha(dotColor, ui.animIntensity > 0 ? pulse : 1f));
			ui.tooltip("status", siq.tracker.describeMode() + (siq.tracker.currentPlaceName() != null ? ": " + siq.tracker.currentPlaceName() : ""));
		} else {
			ui.hline(10, fy - 6, sideW - 20, ui.border);
			ui.dot(16, fy + 4, 3, Ui.withAlpha(dotColor, ui.animIntensity > 0 ? pulse : 1f));
			ui.text(rec ? "Recording" : "Not recording", 24, fy, rec ? ui.text : ui.text2);
			String place = siq.tracker.currentPlaceName();
			ui.textClip(place != null ? place : siq.tracker.describeMode(), 12, fy + ui.lineH() + 4, sideW - 20, ui.text3);
		}
	}

	private void renderTopBar(Page page, int x, int y, int w, int h) {
		ui.hline(x, y + h - 1, w, ui.border);
		int cx = x + 12;
		if (stack.size() > 1) {
			if (ui.iconButton("top#back", x + 8, y + (h - 18) / 2, 18, Icons.BACK, "Back (Esc)")) {
				back();
				return;
			}
			cx = x + 32;
		}
		int actionsLeft = page.renderHeaderActions(ui, x + w - 10, y + (h - ui.buttonH()) / 2, ui.buttonH());
		int avail = actionsLeft - cx - 10;
		List<Page> chain = new ArrayList<>(stack);
		java.util.Collections.reverse(chain);
		int ty = y + (h - ui.capH()) / 2 + 1;
		if (chain.size() > 1 && avail > 160) {
			int bx = cx;
			for (int i = 0; i < chain.size() - 1; i++) {
				String label = ui.ellipsize(chain.get(i).crumb(), 90);
				if (ui.link("crumb#" + i, label, bx, ty, ui.text3)) {
					while (stack.size() > i + 1) {
						stack.pop();
					}
					shown();
					return;
				}
				bx += ui.textW(label) + 4;
				ui.text("/", bx, ty, ui.text3);
				bx += ui.textW("/") + 4;
			}
			ui.textClip(page.title(), bx, ty, Math.max(20, actionsLeft - bx - 10), ui.text);
		} else {
			ui.textClip(page.title(), cx, ty, Math.max(20, avail), ui.text);
		}
	}

	private void renderStorageBanner(int x, int h, int w) {
		StorageService.Issue err = siq.storage.lastWriteError();
		String failure = siq.startupFailure();
		if (err == null && failure == null) {
			return;
		}
		String msg = failure != null ? failure : "Saving failed: " + err.detail() + ". Recent changes may not be on disk.";
		int bh = ui.lineH() + 10;
		int by = h - bh;
		ui.fill(x, by, w, bh, Ui.argb(Ui.mix(ui.th.bg2, ui.th.error, 0.25f), 0.98f));
		ui.hline(x, by, w, Ui.withAlpha(ui.error, 0.7f));
		ui.icon(Icons.WARNING, x + 10, by + (bh - 9) / 2, ui.error);
		ui.textClip(msg, x + 24, by + 5, w - 100, ui.text);
		if (err != null && ui.button("banner#dismiss", x + w - 70, by + 2, 60, bh - 4, "Dismiss", Ui.Btn.GHOST)) {
			siq.storage.clearWriteError();
		}
	}

	private void renderModals(int w, int h) {
		modals.removeIf(Modal::isClosed);
		for (int i = 0; i < modals.size(); i++) {
			Modal m = modals.get(i);
			ui.setLayer(Ui.L_MODAL);
			float t = ui.anim("modal#" + System.identityHashCode(m), 1f, 16f);
			ui.fill(0, 0, w, h, Ui.argb(0x000000, (ui.th.dark ? 0.55f : 0.35f) * t));
			ui.hit("modal#backdrop" + i, 0, 0, w, h);
			int mw = Math.min(m.preferredWidth(ui), w - 24);
			int titleH = 28;
			int buttonsH = ui.buttonH() + 18;
			int bodyW = mw - 2 * ui.pad();
			int bodyH = m.bodyHeight(ui, bodyW);
			int maxBody = h - 40 - titleH - buttonsH;
			boolean scroll = bodyH > maxBody;
			int shownBody = Math.min(bodyH, maxBody);
			int mh = titleH + shownBody + buttonsH;
			int mx = (w - mw) / 2;
			int my = (h - mh) / 2 + Math.round((1 - t) * 10);
			ui.hit("modal#panel" + i, mx, my, mw, mh);
			ui.panel(mx, my, mw, mh, Ui.argb(ui.th.surface, 1f), ui.border, Math.max(ui.th.radius, 2));
			ui.textMid(m.title(), mx + ui.pad(), my, titleH, ui.text);
			if (ui.iconButton("modal#close" + i, mx + mw - 24, my + 5, 18, Icons.CLOSE, "Close (Esc)")) {
				m.close();
			}
			ui.hline(mx, my + titleH, mw, ui.border);
			if (scroll) {
				String sid = "modal#scroll" + i;
				int off = ui.beginScroll(sid, mx, my + titleH + 1, mw, shownBody);
				m.renderBody(ui, mx + ui.pad(), my + titleH + ui.pad() / 2 - off, bodyW);
				ui.endScroll(sid, bodyH + ui.pad());
			} else {
				m.renderBody(ui, mx + ui.pad(), my + titleH + ui.pad() / 2, bodyW);
			}
			int by = my + titleH + shownBody;
			ui.hline(mx, by, mw, ui.border);
			m.renderButtons(ui, mx + ui.pad(), by + 9, mw - 2 * ui.pad(), ui.buttonH());
		}
		ui.setLayer(Ui.L_BASE);
	}

	private void renderToasts(int w, int h) {
		long now = System.currentTimeMillis();
		toasts.removeIf(t -> now - t.at() > (t.action() != null ? 9000 : 5000));
		int y = h - 10;
		ui.setLayer(Ui.L_TOP);
		for (int i = toasts.size() - 1; i >= 0; i--) {
			Toast t = toasts.get(i);
			int tw = Math.min(300, w - 40);
			int textW = tw - 24 - (t.action() != null ? ui.textW(t.action()) + 18 : 0);
			List<net.minecraft.text.OrderedText> lines = ui.wrap(t.text(), textW);
			int th = Math.max(26, lines.size() * (ui.lineH() + 2) + 12);
			y -= th;
			float in = ui.anim("toast#" + t.at() + i, 1f, 14f);
			int x = w - tw - 10 + Math.round((1 - in) * 20);
			int color = t.kind() == TOAST_ERROR ? ui.error : t.kind() == TOAST_SUCCESS ? ui.success : ui.accent;
			ui.hit("toast#" + t.at(), x, y, tw, th);
			ui.panel(x, y, tw, th, Ui.argb(ui.th.surface2, 0.98f), ui.border, ui.th.radius);
			ui.fill(x, y + 4, 2, th - 8, color);
			for (int l = 0; l < lines.size(); l++) {
				ui.text(lines.get(l), x + 10, y + 7 + l * (ui.lineH() + 2), ui.text);
			}
			if (t.action() != null) {
				int bw = ui.textW(t.action()) + 12;
				if (ui.button("toast#a" + t.at(), x + tw - bw - 6, y + (th - 16) / 2, bw, 16, t.action(), Ui.Btn.GHOST)) {
					t.onAction().run();
				}
			}
			y -= 6;
		}
		ui.setLayer(Ui.L_BASE);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		ui.onMouseDown(click.x(), click.y(), click.button(), doubled);
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		ui.onMouseUp();
		return true;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		ui.onMouseMove(click.x(), click.y());
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		ui.onMouseMove(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return ui.onScroll(mouseX, mouseY, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		boolean esc = input.key() == GLFW.GLFW_KEY_ESCAPE;
		if (ui.popupOpen() && esc) {
			ui.closePopup();
			return true;
		}
		TextField field = ui.focusedField();
		if (field != null) {
			if (esc) {
				ui.blur();
				return true;
			}
			if (input.key() == GLFW.GLFW_KEY_TAB) {
				ui.cycleFocus(input.hasShift());
				return true;
			}
			if (field.keyPressed(input, ui)) {
				return true;
			}
		}
		if (!modals.isEmpty()) {
			Modal top = modals.getLast();
			if (top.keyPressed(input, ui)) {
				return true;
			}
			if (esc) {
				top.close();
			}
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_TAB) {
			ui.cycleFocus(input.hasShift());
			return true;
		}
		if (stack.peek().keyPressed(input, ui)) {
			return true;
		}
		if (esc) {
			if (stack.size() > 1) {
				back();
			} else {
				close();
			}
			return true;
		}
		if (field == null && SessionIQClient.openKey != null && SessionIQClient.openKey.matchesKey(input)) {
			close();
			return true;
		}
		if (field == null && input.hasAlt() && input.key() == GLFW.GLFW_KEY_LEFT) {
			back();
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(CharInput input) {
		TextField field = ui.focusedField();
		if (field != null) {
			return field.charTyped(input, ui);
		}
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void close() {
		siq.config.saveNow();
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
