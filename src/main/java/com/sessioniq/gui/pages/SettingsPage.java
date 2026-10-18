package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.SessionIQClient;
import com.sessioniq.backup.BackupService;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.Async;
import com.sessioniq.gui.ColorPickerModal;
import com.sessioniq.gui.ExportModal;
import com.sessioniq.gui.FolderPicker;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.MessageModal;
import com.sessioniq.gui.Modal;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.PromptModal;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.storage.DataActions;
import com.sessioniq.storage.IntegrityChecker;
import com.sessioniq.storage.Migrations;
import com.sessioniq.storage.StorageService;
import com.sessioniq.theme.Theme;
import com.sessioniq.theme.ThemePresets;
import com.sessioniq.track.HookCheck;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsPage extends Page {
	private static final String[] TABS = {"General", "Recording", "Privacy", "Online", "Appearance", "Storage", "Advanced", "Credits"};
	private static final String CREATOR = "Hoops";
	private static final String[] EARLY_FEEDBACK = {"KarmaCritz", "Domusz", "juicebox", "Arweku"};
	private int tab;
	private boolean capturingKey;
	private Theme editing;
	private Theme editingSource;
	private Float pendingUiScale;
	private Float pendingFontScale;
	private final Async<IntegrityChecker.Usage> usage = new Async<>();
	private final Async<List<BackupService.BackupInfo>> backups = new Async<>();
	private final Async<IntegrityChecker.Report> integrity = new Async<>();
	private final Map<Path, BackupService.VerifyResult> verified = new HashMap<>();
	private boolean backupBusy;
	private int backupsRevision;

	public SettingsPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Settings";
	}

	private SiqConfig cfg() {
		return siq.config.get();
	}

	public void showTab(String name) {
		int i = java.util.Arrays.asList(TABS).indexOf(name);
		if (i >= 0) {
			tab = i;
			capturingKey = false;
		}
	}

	private void changed() {
		siq.config.changed();
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = w - pad * 2;
		int sel = ui.tabs("set#tabs", cx, y + 6, cw, TABS, tab);
		if (sel >= 0 && sel != tab) {
			tab = sel;
			capturingKey = false;
		}
		int bodyY = y + 6 + ui.rowH() + 1;
		String sid = "set#body" + tab;
		int off = ui.beginScroll(sid, x, bodyY, w, y + h - bodyY);
		int cy = bodyY + pad - off;
		int maxW = Math.min(cw, 640);
		int used = switch (tab) {
			case 1 -> recording(ui, cx, cy, maxW);
			case 2 -> privacy(ui, cx, cy, maxW);
			case 3 -> online(ui, cx, cy, maxW);
			case 4 -> appearance(ui, cx, cy, cw);
			case 5 -> storage(ui, cx, cy, maxW);
			case 6 -> advanced(ui, cx, cy, maxW);
			case 7 -> credits(ui, cx, cy, maxW);
			default -> general(ui, cx, cy, maxW);
		};
		ui.endScroll(sid, used + pad * 2);
		if (pendingUiScale != null && !ui.mouseDown) {
			cfg().uiScale = pendingUiScale;
			pendingUiScale = null;
			changed();
		}
		if (pendingFontScale != null && !ui.mouseDown) {
			cfg().fontScale = pendingFontScale;
			pendingFontScale = null;
			changed();
		}
	}

	private int section(Ui ui, int x, int y, int w, String title) {
		ui.text(title.toUpperCase(), x, y, ui.text3);
		ui.hline(x + ui.textW(title.toUpperCase()) + 8, y + 4, w - ui.textW(title.toUpperCase()) - 8, ui.border);
		return ui.lineH() + 10;
	}

	private int toggleRow(Ui ui, String id, int x, int y, int w, String label, String desc, boolean value, Runnable onToggle) {
		int descH = desc == null ? 0 : ui.paragraphHeight(desc, w - 50);
		ui.text(label, x, y + 2, ui.text);
		if (desc != null) {
			ui.paragraph(desc, x, y + ui.lineH() + 5, w - 50, ui.text2);
		}
		if (ui.toggle(id, x + w - 24, y + 1, value)) {
			onToggle.run();
		}
		return ui.lineH() + 6 + descH + 10;
	}

	private int labelRow(Ui ui, int x, int y, int w, String label, String desc, int controlW) {
		ui.text(label, x, y + 4, ui.text);
		int dh = 0;
		if (desc != null) {
			dh = ui.paragraph(desc, x, y + ui.lineH() + 7, w - controlW - 16, ui.text2);
		}
		return Math.max(ui.buttonH() + 4, ui.lineH() + 8 + dh) + 10;
	}

	private int general(Ui ui, int x, int y, int w) {
		int cy = y;
		cy += section(ui, x, cy, w, "Dashboard");
		KeyBinding key = SessionIQClient.openKey;
		String keyName = capturingKey ? "Press a key… (Esc cancels)" : key.getBoundKeyLocalizedText().getString();
		int kw = Math.max(110, ui.textW(keyName) + 20);
		int rowStart = cy;
		int rh = labelRow(ui, x, cy, w, "Open SessionIQ", "Also configurable in Options › Controls › Key Binds, under SessionIQ.", kw + 150);
		if (ui.button("set#key", x + w - kw - 140, cy, kw, ui.buttonH(), keyName, capturingKey ? Ui.Btn.PRIMARY : Ui.Btn.SECONDARY)) {
			capturingKey = !capturingKey;
		}
		if (ui.button("set#keyreset", x + w - 134, cy, 56, ui.buttonH(), "Default", Ui.Btn.GHOST)) {
			key.setBoundKey(key.getDefaultKey());
			KeyBinding.updateKeysByCode();
			MinecraftClient.getInstance().options.write();
		}
		if (ui.button("set#controls", x + w - 74, cy, 74, ui.buttonH(), "Controls…", Ui.Btn.GHOST)) {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.setScreen(new KeybindsScreen(screen, mc.options));
		}
		cy += rh;
		List<String> conflicts = new ArrayList<>();
		List<String> debugConflicts = new ArrayList<>();
		if (!key.isUnbound()) {
			for (KeyBinding other : MinecraftClient.getInstance().options.allKeys) {
				if (other != key && other.equals(key)) {
					String name = net.minecraft.text.Text.translatable(other.getId()).getString();
					(other.getCategory() == KeyBinding.Category.DEBUG ? debugConflicts : conflicts).add(name);
				}
			}
		} else {
			ui.text("The key is unbound. Bind one here or in Controls, otherwise the dashboard cannot be opened.", x, cy - 6, ui.warning);
			cy += ui.lineH() + 4;
		}
		if (!conflicts.isEmpty()) {
			int ph = ui.paragraph("Also bound to: " + String.join(", ", conflicts) + ". Both actions will trigger; choose another key to avoid this.", x + 14, cy - 4, w - 14, ui.warning);
			ui.icon(Icons.WARNING, x, cy - 4, ui.warning);
			cy += ph + 6;
		}
		if (!debugConflicts.isEmpty()) {
			int ph = ui.paragraph("Also used with F3 for: " + String.join(", ", debugConflicts) + ". Those only trigger while F3 is held.", x + 14, cy - 4, w - 14, ui.text2);
			ui.icon(Icons.INFO, x, cy - 4, ui.text3);
			cy += ph + 6;
		}

		List<SiqScreen.NavItem> choices = SiqScreen.NAV.stream().filter(screen::showInSidebar).toList();
		List<String> pages = new ArrayList<>();
		int pageIndex = 0;
		for (int i = 0; i < choices.size(); i++) {
			pages.add(choices.get(i).label());
			if (choices.get(i).key().equals(cfg().defaultPage)) {
				pageIndex = i;
			}
		}
		rh = labelRow(ui, x, cy, w, "Default page", "Shown when the dashboard opens.", 150);
		int pi = ui.dropdown("set#defpage", x + w - 150, cy, 150, ui.buttonH(), pages, pageIndex);
		if (pi != pageIndex) {
			cfg().defaultPage = choices.get(pi).key();
			changed();
		}
		cy += rh;

		cy += section(ui, x, cy, w, "Date and time");
		rh = labelRow(ui, x, cy, w, "Clock", null, 120);
		int clock = ui.segmented("set#clock", x + w - ui.segmentedWidth(new String[]{"24-hour", "12-hour"}), cy, new String[]{"24-hour", "12-hour"}, cfg().clock24h ? 0 : 1);
		if (clock >= 0) {
			cfg().clock24h = clock == 0;
			changed();
		}
		cy += rh;
		String[] formats = {"YMD", "DMY", "MDY"};
		List<String> formatLabels = List.of("2026-10-15", "15/10/2026", "10/15/2026");
		int fi = java.util.Arrays.asList(formats).indexOf(cfg().dateFormat);
		rh = labelRow(ui, x, cy, w, "Date format", null, 150);
		int f = ui.dropdown("set#datefmt", x + w - 150, cy, 150, ui.buttonH(), formatLabels, Math.max(0, fi));
		if (f != fi && f >= 0) {
			cfg().dateFormat = formats[f];
			changed();
		}
		cy += rh;
		String sys = ZoneId.systemDefault().getId();
		List<String> zones = List.of("System (" + sys + ")", "UTC");
		int zi = "UTC".equals(cfg().timeZone) ? 1 : 0;
		rh = labelRow(ui, x, cy, w, "Time zone", "Records are stored in UTC; this only changes how times are shown and where days begin in statistics.", 170);
		int z = ui.dropdown("set#tz", x + w - 170, cy, 170, ui.buttonH(), zones, zi);
		if (z != zi) {
			cfg().timeZone = z == 1 ? "UTC" : "system";
			changed();
		}
		cy += rh;
		cy += toggleRow(ui, "set#showtz", x, cy, w, "Show time zone", "Append the zone abbreviation to displayed times.", cfg().showTimeZone, () -> {
			cfg().showTimeZone = !cfg().showTimeZone;
			changed();
		});
		cy += section(ui, x, cy, w, "Interface");
		cy += scaleRows(ui, x, cy, w);
		return cy - y;
	}

	private int scaleRows(Ui ui, int x, int y, int w) {
		int cy = y;
		float shownUi = pendingUiScale != null ? pendingUiScale : cfg().uiScale;
		int rh = labelRow(ui, x, cy, w, "Interface scale", "Size of the dashboard; 100% fits it to your window independently of Minecraft's GUI scale. Applied when you release the slider.", 170);
		float v = ui.slider("set#uiscale", x + w - 170, cy + 4, 130, shownUi, 0.75f, 1.5f, 0.05f);
		ui.textRight(Math.round(v * 100) + "%", x + w, cy + 4, ui.text2);
		if (Math.abs(v - cfg().uiScale) > 0.001f) {
			pendingUiScale = v;
		}
		cy += rh;
		float shownFont = pendingFontScale != null ? pendingFontScale : cfg().fontScale;
		rh = labelRow(ui, x, cy, w, "Font scale", null, 170);
		float fv = ui.slider("set#fontscale", x + w - 170, cy + 4, 130, shownFont, 0.75f, 1.5f, 0.05f);
		ui.textRight(Math.round(fv * 100) + "%", x + w, cy + 4, ui.text2);
		if (Math.abs(fv - cfg().fontScale) > 0.001f) {
			pendingFontScale = fv;
		}
		cy += rh;
		return cy - y;
	}

	private int recording(Ui ui, int x, int y, int w) {
		int cy = y;
		SiqConfig c = cfg();
		cy += section(ui, x, cy, w, "What to record");
		cy += toggleRow(ui, "set#track", x, cy, w, "Session tracking", "Master switch. When off, SessionIQ records nothing; the current session is closed.",
			c.trackSessions, () -> {
				c.trackSessions = !c.trackSessions;
				changed();
			});
		cy += toggleRow(ui, "set#mp", x, cy, w, "Multiplayer servers", "Visits, durations, connection attempts and disconnects for servers, LAN and Realms.",
			c.trackMultiplayer, () -> {
				c.trackMultiplayer = !c.trackMultiplayer;
				changed();
			});
		cy += toggleRow(ui, "set#sp", x, cy, w, "Single-player worlds", "Time spent in your own worlds, tracked separately from servers.", c.trackSingleplayer, () -> {
			c.trackSingleplayer = !c.trackSingleplayer;
			changed();
		});
		cy += toggleRow(ui, "set#chat", x, cy, w, "Chat archive", "Player chat you receive. Commands you send are never recorded.", c.recordChat, () -> {
			c.recordChat = !c.recordChat;
			changed();
		});
		cy += toggleRow(ui, "set#system", x, cy, w, "System messages", "Server and game messages shown in the chat box (not the action bar).", c.recordSystemMessages, () -> {
			c.recordSystemMessages = !c.recordSystemMessages;
			changed();
		});
		cy += toggleRow(ui, "set#format", x, cy, w, "Chat colours", "Keep colours and bold/italic styling so messages look as they did. Links and hover actions are never stored.",
			c.recordChatFormatting, () -> {
				c.recordChatFormatting = !c.recordChatFormatting;
				changed();
			});
		cy += toggleRow(ui, "set#deaths", x, cy, w, "Deaths", "Mark deaths (with the death message) on the timeline.", c.recordDeaths, () -> {
			c.recordDeaths = !c.recordDeaths;
			changed();
		});
		cy += section(ui, x, cy, w, "Performance sampling");
		cy += toggleRow(ui, "set#ping", x, cy, w, "Ping", "The latency the server reports for you in the player list. Servers that hide it show as unavailable.",
			c.recordPing, () -> {
				c.recordPing = !c.recordPing;
				changed();
			});
		if (c.recordPing) {
			int rh = labelRow(ui, x + 12, cy, w - 12, "Ping interval", null, 170);
			float v = ui.slider("set#pingint", x + w - 170, cy + 4, 130, c.pingIntervalSec, 2, 60, 1);
			ui.textRight((int) v + " s", x + w, cy + 4, ui.text2);
			if ((int) v != c.pingIntervalSec) {
				c.pingIntervalSec = (int) v;
				if (!ui.mouseDown) {
					changed();
				}
			}
			cy += rh;
		}
		cy += toggleRow(ui, "set#mem", x, cy, w, "Memory", "Used Java heap memory (not allocated memory) and the maximum heap.", c.recordMemory, () -> {
			c.recordMemory = !c.recordMemory;
			changed();
		});
		if (c.recordMemory) {
			int rh = labelRow(ui, x + 12, cy, w - 12, "Memory interval", null, 170);
			float v = ui.slider("set#memint", x + w - 170, cy + 4, 130, c.memoryIntervalSec, 5, 300, 5);
			ui.textRight((int) v + " s", x + w, cy + 4, ui.text2);
			if ((int) v != c.memoryIntervalSec) {
				c.memoryIntervalSec = (int) v;
				if (!ui.mouseDown) {
					changed();
				}
			}
			cy += rh;
		}
		if (!ui.mouseDown && (ui.isDragging("set#pingint") || ui.isDragging("set#memint"))) {
			changed();
		}
		return cy - y;
	}

	private int privacy(Ui ui, int x, int y, int w) {
		int cy = y;
		String statement = "Your recorded history stays on this computer. The optional online features (Settings › Online) upload only a summary: totals, "
			+ "playtime and joins per public server, and what you let friends see. Chat, disconnects and timelines are never uploaded. "
			+ "Outgoing commands, anything you type, passwords and keystrokes are never recorded; only messages the server sends to your chat are archived, "
			+ "and server replies that repeat a command you just sent are discarded too. "
			+ "Your archive can still contain sensitive information, such as server addresses and other players' messages.";
		int ph = ui.paragraphHeight(statement, w - 40) + 16;
		ui.panel(x, cy, w, ph, Ui.argb(Ui.mix(ui.th.surface, ui.th.accent, 0.08f), 1f), Ui.withAlpha(ui.accent, 0.4f), ui.radius());
		ui.icon(Icons.EYE, x + 10, cy + 9, ui.accent);
		ui.paragraph(statement, x + 26, cy + 8, w - 40, ui.text);
		cy += ph + 14;
		cy += section(ui, x, cy, w, "Display");
		cy += toggleRow(ui, "set#addr", x, cy, w, "Show server addresses", "Hide addresses on screen (for streaming or screenshots). Stored data is unchanged.",
			cfg().showAddresses, () -> {
				cfg().showAddresses = !cfg().showAddresses;
				changed();
			});
		cy += toggleRow(ui, "set#merge", x, cy, w, "Merge ports of the same host", "Group play.example.com:25565 and :25566 as one server in lists and statistics.",
			cfg().mergePorts, () -> {
				cfg().mergePorts = !cfg().mergePorts;
				ServerNames.invalidate();
				changed();
			});
		cy += section(ui, x, cy, w, "Your data");
		ui.text("Stored in", x, cy + 2, ui.text2);
		cy += ui.lineH() + 4;
		ui.textClip(siq.paths.root.toString(), x, cy + 2, w - 110, ui.text);
		if (ui.button("set#open", x + w - 100, cy - 3, 100, ui.buttonH(), "Open folder", Icons.FOLDER, Ui.Btn.SECONDARY, true)) {
			Util.getOperatingSystem().open(siq.paths.root);
		}
		cy += ui.lineH() + 14;
		int ew = ui.buttonWidth("Export history…", Icons.EXPORT);
		if (ui.button("set#export", x, cy, ew, ui.buttonH(), "Export history…", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			openExportAll();
		}
		cy += ui.buttonH() + 14;
		ui.paragraph("To delete individual records, open them: sessions, servers, chat messages (use Select in Chat History) and disconnect events each have a Delete action.",
			x, cy, w, ui.text2);
		cy += ui.paragraphHeight("To delete individual records, open them: sessions, servers, chat messages (use Select in Chat History) and disconnect events each have a Delete action.", w) + 10;
		cy += section(ui, x, cy, w, "Delete everything");
		int bw = ui.buttonWidth("Clear all chat history", Icons.TRASH);
		if (ui.button("set#clearchat", x, cy, bw, ui.buttonH(), "Clear all chat history", Icons.TRASH, Ui.Btn.DANGER, siq.isReady())) {
			long total = siq.sessions.sorted().stream().mapToLong(s -> s.chatCount).sum();
			screen.confirm("Clear all chat history?", "Permanently deletes every archived chat message (" + Fmt.count(total)
					+ " recorded). Sessions, servers, disconnects and statistics other than chat counts are kept. Backups are not affected; delete them separately if needed.",
				"Delete all chat", true, () -> DataActions.clearAllChat(siq).whenCompleteAsync((v, e) -> screen.toast(e == null ? "Chat history cleared"
					: "Could not clear chat: " + e.getMessage(), e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR), SessionIQ.mainThread()));
		}
		int bw2 = ui.buttonWidth("Clear all recorded history", Icons.TRASH);
		if (ui.button("set#clearall", x + bw + 8, cy, bw2, ui.buttonH(), "Clear all recorded history", Icons.TRASH, Ui.Btn.DANGER, siq.isReady())) {
			screen.confirm("Clear ALL recorded history?", "Permanently deletes every session (" + Fmt.count(siq.sessions.size())
					+ "), server visit, disconnect event, chat message, sample, nickname and group. The current session is closed and a new one starts. "
					+ "Settings, themes and backups are kept. Consider exporting or making a backup first.",
				"Delete everything", true, () -> screen.openModal(new com.sessioniq.gui.ConfirmModal("Are you sure?",
					"This cannot be undone from within SessionIQ (only by restoring a backup).", "Yes, delete all history", true,
					() -> DataActions.clearAllHistory(siq).whenCompleteAsync((v, e) -> screen.toast(e == null ? "All history deleted" : "Deletion failed: " + e.getMessage(),
						e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR), SessionIQ.mainThread()))));
		}
		cy += ui.buttonH() + 10;
		return cy - y;
	}

	private int online(Ui ui, int x, int y, int w) {
		int cy = y;
		SiqConfig c = cfg();
		com.sessioniq.online.OnlineService online = siq.online;
		com.sessioniq.online.OnlineService.Status st = online.status();
		String status;
		int color;
		String action = null;
		switch (st) {
			case UNAVAILABLE -> {
				status = "Online features are not available in this build of SessionIQ.";
				color = ui.text2;
			}
			case DISABLED -> {
				status = "Online features are off. Nothing is uploaded and friends cannot see you.";
				color = ui.text2;
			}
			case NEEDS_NOTICE -> {
				status = "Paused until you review what is shared. Nothing has been uploaded.";
				color = ui.warning;
				action = "Review";
			}
			case SIGNING_IN -> {
				status = "Signing in with your Minecraft account…";
				color = ui.text2;
			}
			case SIGNED_OUT -> {
				status = "Not signed in: " + online.signInError() + " Global Statistics and Suggestions still work.";
				color = ui.warning;
				action = "Try again";
			}
			default -> {
				status = "Signed in as " + online.name() + (online.isAdmin() ? " (admin)" : "") + ".";
				color = ui.success;
			}
		}
		int aw = action == null ? 0 : ui.buttonWidth(action, null) + 10;
		int ph = ui.paragraphHeight(status, w - 40 - aw) + 16;
		ui.panel(x, cy, w, Math.max(ph, ui.buttonH() + 12), Ui.argb(Ui.mix(ui.th.surface, ui.th.accent, 0.06f), 1f), Ui.withAlpha(color, 0.5f), ui.radius());
		ui.icon(st == com.sessioniq.online.OnlineService.Status.SIGNED_IN ? Icons.CHECK : Icons.GLOBE, x + 10, cy + 9, color);
		ui.paragraph(status, x + 26, cy + 8, w - 40 - aw, ui.text);
		if (action != null && ui.button("set#onlineaction", x + w - aw, cy + 6, aw - 10, ui.buttonH(), action, Ui.Btn.SECONDARY)) {
			if (st == com.sessioniq.online.OnlineService.Status.NEEDS_NOTICE) {
				screen.openModal(new com.sessioniq.gui.OnlineNoticeModal(screen));
			} else {
				online.retrySignIn();
			}
		}
		cy += Math.max(ph, ui.buttonH() + 12) + 14;
		if (st == com.sessioniq.online.OnlineService.Status.UNAVAILABLE) {
			return cy - y;
		}

		cy += section(ui, x, cy, w, "Online features");
		cy += toggleRow(ui, "set#online", x, cy, w, "Online features", "Global Statistics, Friends and Suggestions. When off, SessionIQ makes no network requests at all.",
			c.onlineEnabled, () -> {
				c.onlineEnabled = !c.onlineEnabled;
				changed();
			});
		cy += section(ui, x, cy, w, "Global Statistics");
		cy += toggleRow(ui, "set#shareglobal", x, cy, w, "Show me in Global Statistics",
			"Your username, total playtime, sessions, servers joined and most-played public server appear on the leaderboards, and your public servers count towards the server totals. "
				+ "Turning this off removes your servers from the totals and you from the leaderboards.",
			c.shareGlobalStats, () -> {
				c.shareGlobalStats = !c.shareGlobalStats;
				changed();
			});
		cy += section(ui, x, cy, w, "What friends can see");
		cy += toggleRow(ui, "set#fonline", x, cy, w, "Online status", "Whether you are online, and when you were last seen.", c.friendsShowOnline, () -> {
			c.friendsShowOnline = !c.friendsShowOnline;
			changed();
		});
		cy += toggleRow(ui, "set#fserver", x, cy, w, "Current server", "The public server you are playing on. Private servers, LAN and single-player only show as such, never their address.",
			c.friendsShowServer, () -> {
				c.friendsShowServer = !c.friendsShowServer;
				changed();
			});
		cy += toggleRow(ui, "set#fstats", x, cy, w, "Your stats", "Playtime, sessions, servers joined and your most-played server.", c.friendsShowStats, () -> {
			c.friendsShowStats = !c.friendsShowStats;
			changed();
		});
		cy += toggleRow(ui, "set#fchat", x, cy, w, "Friend chat", "Let friends message you. Messages are deleted from the SessionIQ server after 7 days.", c.friendsAllowChat, () -> {
			c.friendsAllowChat = !c.friendsAllowChat;
			changed();
		});
		cy += toggleRow(ui, "set#fnotify", x, cy, w, "Notifications", "Show a pop-up for new messages and friend requests while you play.", c.friendNotifications, () -> {
			c.friendNotifications = !c.friendNotifications;
			changed();
		});

		cy += section(ui, x, cy, w, "Your data");
		String never = "Never uploaded: chat logs, disconnects, timelines, single-player worlds, and private or LAN server addresses. "
			+ "To sign in, SessionIQ uses the Mojang-signed key Minecraft already has for secure chat; your Minecraft login is never sent to SessionIQ.";
		cy += ui.paragraph(never, x, cy, w, ui.text2) + 10;
		int bx = x;
		int vw = ui.buttonWidth("View what's sent", Icons.EYE);
		if (ui.button("set#whatsent", bx, cy, vw, ui.buttonH(), "View what's sent", Icons.EYE, Ui.Btn.SECONDARY, siq.isReady())) {
			screen.openModal(new com.sessioniq.gui.WhatsSentModal());
		}
		bx += vw + 6;
		int nw = ui.buttonWidth("Read the notice again", null);
		if (ui.button("set#notice", bx, cy, nw, ui.buttonH(), "Read the notice again", Ui.Btn.GHOST)) {
			screen.openModal(new com.sessioniq.gui.OnlineNoticeModal(screen));
		}
		cy += ui.buttonH() + 10;
		int dw = ui.buttonWidth("Delete my online data", Icons.TRASH);
		if (ui.button("set#deleteonline", x, cy, dw, ui.buttonH(), "Delete my online data", Icons.TRASH, Ui.Btn.DANGER, true)) {
			screen.confirm("Delete your online data?", "Removes your stats, server totals, friends, friend requests and messages from the SessionIQ server, and forgets your "
					+ "anonymous launch counter. Online features are then turned off. Your local history is not affected.", "Delete online data", true,
				() -> online.deleteOnlineData().whenCompleteAsync((v, e) -> screen.toast(e == null ? "Your online data was deleted and online features are off"
					: "Could not delete: " + com.sessioniq.online.ApiClient.message(e), e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR), SessionIQ.mainThread()));
		}
		cy += ui.buttonH() + 10;
		if (online.serviceUrl() != null) {
			ui.textClip("Service: " + online.serviceUrl(), x, cy, w, ui.text3);
			cy += ui.lineH() + 4;
		}
		return cy - y;
	}

	private void openExportAll() {
		screen.openModal(new ExportModal(screen, List.of(
			new ExportModal.Option("All sessions", "Every session with visits, disconnects and summaries.", List.of(Format.JSON, Format.CSV),
				f -> siq.exports.sessions(siq.sessions.snapshot(), f)),
			new ExportModal.Option("Server history", "Totals and visit history per server.", List.of(Format.JSON, Format.CSV),
				f -> siq.exports.servers(siq.sessions.snapshot(), f, ServerNames::name, siq.servers::group)),
			new ExportModal.Option("Chat history", "The entire chat archive.", List.of(Format.TEXT, Format.CSV, Format.JSON), f -> {
				var c = new com.sessioniq.analytics.ChatSearch.Criteria();
				c.mergePorts = cfg().mergePorts;
				return siq.exports.chat(siq.sessions.snapshot(), c, f, ServerNames::name);
			}),
			new ExportModal.Option("Disconnect history", "Every disconnect event and failed connection.", List.of(Format.JSON, Format.CSV),
				f -> siq.exports.disconnects(siq.sessions.snapshot(), f, ServerNames::name))
		)));
	}

	private int appearance(Ui ui, int x, int y, int w) {
		int cy = y;
		cy += section(ui, x, cy, w, "Theme");
		List<Theme> all = new ArrayList<>(siq.themes.builtIns());
		all.addAll(siq.themes.custom());
		int cardW = 132;
		int cardH = 66;
		int perRow = Math.max(1, (w + ui.gap()) / (cardW + ui.gap()));
		Theme active = siq.themes.active();
		for (int i = 0; i < all.size(); i++) {
			Theme t = all.get(i);
			int tx = x + (i % perRow) * (cardW + ui.gap());
			int ty = cy + (i / perRow) * (cardH + ui.gap());
			String id = "set#theme" + t.name;
			ui.hit(id, tx, ty, cardW, cardH);
			float hv = ui.hoverAnim(id);
			boolean sel = editing == null && t.name.equals(active.name) || editing != null && t == editingSource;
			ui.roundRect(tx, ty, cardW, cardH, Math.max(2, t.radius), 0xFF000000 | t.bg);
			ui.fill(tx + 1, ty + 1, 26, cardH - 2, 0xFF000000 | t.bg2);
			ui.roundRect(tx + 6, ty + 8, 14, 4, 1, 0xFF000000 | t.accent);
			ui.fill(tx + 6, ty + 16, 14, 2, 0xFF000000 | t.text2);
			ui.fill(tx + 6, ty + 21, 14, 2, 0xFF000000 | t.text2);
			ui.roundRect(tx + 32, ty + 8, cardW - 40, 26, Math.min(4, t.radius), 0xFF000000 | t.surface);
			for (int c = 0; c < 4; c++) {
				ui.fill(tx + 38 + c * 9, ty + 26 - c * 3, 6, 4 + c * 3, 0xFF000000 | t.chart[c]);
			}
			ui.text(ui.ellipsize(t.name, cardW - 12), tx + 32, ty + cardH - 22, 0xFF000000 | t.text);
			ui.text(t.builtIn ? "Built-in" : "Custom", tx + 32, ty + cardH - 12, 0xFF000000 | t.text2);
			ui.roundBorder(tx, ty, cardW, cardH, Math.max(2, t.radius), sel ? ui.accent : Ui.mixArgb(ui.border, ui.text2, hv * 0.5f));
			if (sel) {
				ui.roundBorder(tx + 1, ty + 1, cardW - 2, cardH - 2, Math.max(1, t.radius - 1), ui.accent);
			}
			if (ui.clicked(id) && editing == null) {
				siq.themes.select(t.name);
				cfg().theme = t.name;
				changed();
			}
		}
		cy += ((all.size() + perRow - 1) / perRow) * (cardH + ui.gap()) + 4;
		if (editing == null) {
			int bx = x;
			int nw = ui.buttonWidth("Customize…", Icons.EDIT);
			if (ui.button("set#customize", bx, cy, nw, ui.buttonH(), active.builtIn ? "Customize…" : "Edit…", Icons.EDIT, Ui.Btn.SECONDARY, true)) {
				editingSource = active;
				editing = active.copy(active.builtIn ? uniqueName(active.name + " Custom") : active.name);
				siq.themes.preview(editing);
			}
			bx += nw + 6;
			if (!active.builtIn) {
				int dw = ui.buttonWidth("Delete theme", Icons.TRASH);
				if (ui.button("set#deltheme", bx, cy, dw, ui.buttonH(), "Delete theme", Icons.TRASH, Ui.Btn.DANGER, true)) {
					String name = active.name;
					screen.confirm("Delete theme \"" + name + "\"?", "The custom theme is removed. Obsidian becomes active. Built-in themes cannot be deleted.", "Delete", true, () -> {
						String err = siq.themes.deleteCustom(name);
						if (err != null) {
							screen.toast(err, SiqScreen.TOAST_ERROR);
						} else {
							cfg().theme = siq.themes.active().name;
							changed();
						}
					});
				}
			}
			cy += ui.buttonH() + 14;
		} else {
			cy += themeEditor(ui, x, cy, w);
		}
		cy += section(ui, x, cy, w, "Layout");
		int mw = Math.min(w, 640);
		cy += scaleRows(ui, x, cy, mw);
		int rh = labelRow(ui, x, cy, mw, "Density", null, 200);
		String[] dens = {"Comfortable", "Compact"};
		int d = ui.segmented("set#density", x + mw - ui.segmentedWidth(dens), cy, dens, cfg().compact ? 1 : 0);
		if (d >= 0) {
			cfg().compact = d == 1;
			changed();
		}
		cy += rh;
		rh = labelRow(ui, x, cy, mw, "Animation", "0% turns animations off.", 170);
		float a = ui.slider("set#anim", x + mw - 170, cy + 4, 130, cfg().animation, 0f, 1f, 0.05f);
		ui.textRight(Math.round(a * 100) + "%", x + mw, cy + 4, ui.text2);
		if (Math.abs(a - cfg().animation) > 0.001f) {
			cfg().animation = a;
			if (!ui.mouseDown) {
				changed();
			}
		}
		cy += rh;
		return cy - y;
	}

	private String uniqueName(String base) {
		String n = base;
		int i = 2;
		while (siq.themes.find(n) != null) {
			n = base + " " + i++;
		}
		return n;
	}

	private int themeEditor(Ui ui, int x, int y, int w) {
		int cy = y;
		int boxY = cy;
		int innerX = x + 12;
		int innerW = w - 24;
		cy += 12;
		ui.text("Editing theme", innerX, cy, ui.text2);
		cy += ui.lineH() + 6;
		TextField name = ui.field("set#themename");
		if (!name.text.equals(editing.name) && !ui.isFocused("set#themename")) {
			name.set(editing.name);
		}
		name.maxLength = 32;
		ui.textField("set#themename", innerX, cy, Math.min(220, innerW - 160), 20, "Theme name", null);
		if (name.consumeChanged()) {
			editing.name = name.text;
		}
		String[] modes = {"Dark", "Light"};
		int mode = ui.segmented("set#thememode", innerX + innerW - ui.segmentedWidth(modes), cy + 1, modes, editing.dark ? 0 : 1);
		if (mode >= 0 && (mode == 0) != editing.dark) {
			Theme base = ThemePresets.base(mode == 0);
			editing.dark = mode == 0;
			for (Theme.Slot s : new Theme.Slot[]{Theme.Slot.BG, Theme.Slot.BG2, Theme.Slot.SURFACE, Theme.Slot.SURFACE2, Theme.Slot.BORDER, Theme.Slot.TEXT, Theme.Slot.TEXT2}) {
				editing.set(s, base.get(s));
			}
			editing.backdropOpacity = base.backdropOpacity;
		}
		cy += 28;
		Theme.Slot[] slots = Theme.Slot.values();
		int colW = innerW >= 520 ? (innerW - 2 * ui.gap()) / 3 : innerW >= 340 ? (innerW - ui.gap()) / 2 : innerW;
		int per = Math.max(1, (innerW + ui.gap()) / (colW + ui.gap()));
		int slotH = 22;
		for (int i = 0; i < slots.length; i++) {
			Theme.Slot s = slots[i];
			int sx = innerX + (i % per) * (colW + ui.gap());
			int sy = cy + (i / per) * (slotH + 4);
			String id = "set#slot" + s.name();
			ui.hit(id, sx, sy, colW, slotH);
			float hv = ui.hoverAnim(id);
			if (hv > 0) {
				ui.roundRect(sx, sy, colW, slotH, Math.min(3, ui.th.radius), Ui.withAlpha(ui.hover, hv * 1.5f));
			}
			ui.roundRect(sx + 4, sy + 4, 14, 14, Math.min(3, ui.th.radius), 0xFF000000 | editing.get(s));
			ui.roundBorder(sx + 4, sy + 4, 14, 14, Math.min(3, ui.th.radius), ui.border);
			ui.textMid(s.label, sx + 24, sy, slotH, ui.text);
			ui.textMid(Theme.hex(editing.get(s)), sx + colW - 4 - ui.textW("#000000"), sy, slotH, ui.text3);
			if (ui.clicked(id)) {
				Theme target = editing;
				screen.openModal(new ColorPickerModal(s.label, editing.get(s), rgb -> target.set(s, rgb)));
			}
		}
		cy += ((slots.length + per - 1) / per) * (slotH + 4) + 8;
		int sw = Math.min(innerW, 600);
		int rh = labelRow(ui, innerX, cy, sw, "Corner radius", null, 170);
		float r = ui.slider("set#radius", innerX + sw - 170, cy + 4, 130, editing.radius, 0, 8, 1);
		editing.radius = Math.round(r);
		ui.textRight(editing.radius + " px", innerX + sw, cy + 4, ui.text2);
		cy += rh;
		rh = labelRow(ui, innerX, cy, sw, "Panel opacity", null, 170);
		editing.panelOpacity = ui.slider("set#panelop", innerX + sw - 170, cy + 4, 130, editing.panelOpacity, 0.6f, 1f, 0.02f);
		ui.textRight(Math.round(editing.panelOpacity * 100) + "%", innerX + sw, cy + 4, ui.text2);
		cy += rh;
		rh = labelRow(ui, innerX, cy, sw, "Background opacity", "Below 100% the game shows through (blurred) when opened in a world.", 170);
		editing.backdropOpacity = ui.slider("set#backop", innerX + sw - 170, cy + 4, 130, editing.backdropOpacity, 0.5f, 1f, 0.02f);
		ui.textRight(Math.round(editing.backdropOpacity * 100) + "%", innerX + sw, cy + 4, ui.text2);
		cy += rh;
		int bx = innerX;
		int saveW = ui.buttonWidth("Save theme", Icons.CHECK);
		if (ui.button("set#savetheme", bx, cy, saveW, ui.buttonH(), "Save theme", Icons.CHECK, Ui.Btn.PRIMARY, true)) {
			String err = siq.themes.saveCustom(editing);
			if (err != null) {
				screen.toast(err, SiqScreen.TOAST_ERROR);
			} else {
				siq.themes.select(editing.name);
				cfg().theme = editing.name;
				changed();
				screen.toast("Theme \"" + editing.name + "\" saved", SiqScreen.TOAST_SUCCESS);
				editing = null;
				editingSource = null;
			}
		}
		bx += saveW + 6;
		if (editing != null && ui.button("set#resettheme", bx, cy, 60, ui.buttonH(), "Reset", Ui.Btn.SECONDARY)) {
			String keepName = editing.name;
			editing = editingSource.copy(keepName);
			siq.themes.preview(editing);
		}
		bx += 66;
		if (editing != null && ui.button("set#canceltheme", bx, cy, 60, ui.buttonH(), "Cancel", Ui.Btn.GHOST)) {
			editing = null;
			editingSource = null;
			siq.themes.select(cfg().theme);
		}
		cy += ui.buttonH() + 12;
		ui.roundBorder(x, boxY, w, cy - boxY, ui.radius(), Ui.withAlpha(ui.accent, 0.5f));
		return cy - y + 14;
	}

	private int storage(Ui ui, int x, int y, int w) {
		int cy = y;
		usage.ensure(siq.dataRevision() / 5, () -> siq.storage.submitQuery(() -> IntegrityChecker.usage(siq.paths)));
		if (backupsRevision != siq.dataRevision() && !backups.loading()) {
			backupsRevision = siq.dataRevision();
			backups.reload(backupsRevision, () -> siq.storage.submitQuery(siq.backups::list));
		}
		cy += section(ui, x, cy, w, "Usage");
		IntegrityChecker.Usage u = usage.get();
		long visits = 0;
		long chat = 0;
		long dcs = 0;
		for (SessionRecord s : siq.sessions.sorted()) {
			visits += s.visits.size();
			chat += s.chatCount;
			dcs += s.disconnects.size();
		}
		String[][] rows = {
			{"Sessions", Fmt.count(siq.sessions.size()), u == null ? "" : Fmt.bytes(u.sessions())},
			{"Server visits", Fmt.count(visits), ""},
			{"Disconnect events", Fmt.count(dcs), ""},
			{"Chat messages", Fmt.count(chat), u == null ? "" : Fmt.bytes(u.chat())},
			{"Ping and memory samples", "", u == null ? "" : Fmt.bytes(u.samples())},
			{"Backups", backups.get() == null ? "…" : Fmt.count(backups.get().size()), u == null ? "" : Fmt.bytes(u.backups())},
			{"Total", "", u == null ? "Calculating…" : Fmt.bytes(u.total())},
		};
		int rh = ui.rowH();
		ui.card(x, cy, w, rows.length * rh + 10);
		for (int i = 0; i < rows.length; i++) {
			int ry = cy + 5 + i * rh;
			ui.textMid(rows[i][0], x + 12, ry, rh, i == rows.length - 1 ? ui.text : ui.text2);
			ui.textMid(rows[i][1], x + w / 2, ry, rh, ui.text);
			ui.textMid(rows[i][2], x + w - 12 - ui.textW(rows[i][2]), ry, rh, ui.text3);
		}
		cy += rows.length * rh + 22;

		cy += section(ui, x, cy, w, "Automatic backups");
		SiqConfig c = cfg();
		cy += toggleRow(ui, "set#autobackup", x, cy, w, "Automatic backups", "Creates a verified archive of your records on a schedule. Rotation only removes older automatic backups, never your records or manual backups.",
			c.autoBackup, () -> {
				c.autoBackup = !c.autoBackup;
				changed();
			});
		if (c.autoBackup) {
			List<String> intervals = List.of("Every 6 hours", "Every 12 hours", "Daily", "Every 2 days", "Weekly");
			int idx = 2;
			for (int i = 0; i < SiqConfig.BACKUP_INTERVALS.length; i++) {
				if (SiqConfig.BACKUP_INTERVALS[i] == c.backupIntervalHours) {
					idx = i;
				}
			}
			int lr = labelRow(ui, x + 12, cy, w - 12, "Frequency", null, 150);
			int sel = ui.dropdown("set#bint", x + w - 150, cy, 150, ui.buttonH(), intervals, idx);
			if (sel != idx) {
				c.backupIntervalHours = SiqConfig.BACKUP_INTERVALS[sel];
				changed();
			}
			cy += lr;
			lr = labelRow(ui, x + 12, cy, w - 12, "Keep automatic backups", null, 170);
			float v = ui.slider("set#bmax", x + w - 170, cy + 4, 130, c.maxBackups, 1, 50, 1);
			ui.textRight(String.valueOf((int) v), x + w, cy + 4, ui.text2);
			if ((int) v != c.maxBackups) {
				c.maxBackups = (int) v;
				if (!ui.mouseDown) {
					changed();
				}
			}
			cy += lr;
		}

		cy += section(ui, x, cy, w, "Backups");
		int bw = ui.buttonWidth(backupBusy ? "Working…" : "Create backup now", Icons.ARCHIVE);
		if (ui.button("set#backupnow", x, cy, bw, ui.buttonH(), backupBusy ? "Working…" : "Create backup now", Icons.ARCHIVE, Ui.Btn.PRIMARY, !backupBusy && siq.isReady())) {
			backupBusy = true;
			siq.chat.flush();
			siq.tracker.checkpoint();
			siq.storage.submitIo(() -> siq.backups.createNow("manual", false)).whenCompleteAsync((p, e) -> {
				backupBusy = false;
				backupsRevision = -1;
				usage.invalidate();
				screen.toast(e == null ? "Backup created: " + p.getFileName() : "Backup failed: " + cause(e), e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR);
			}, SessionIQ.mainThread());
		}
		if (ui.button("set#openbackups", x + bw + 6, cy, 110, ui.buttonH(), "Open folder", Icons.FOLDER, Ui.Btn.SECONDARY, true)) {
			try {
				java.nio.file.Files.createDirectories(siq.paths.backups);
			} catch (java.io.IOException ignored) {
			}
			Util.getOperatingSystem().open(siq.paths.backups);
		}
		cy += ui.buttonH() + 10;
		List<BackupService.BackupInfo> list = backups.get();
		if (list == null) {
			ui.text("Loading backups…", x, cy, ui.text3);
			cy += ui.lineH() + 10;
		} else if (list.isEmpty()) {
			ui.card(x, cy, w, 60);
			ui.textCenter("No backups yet", x + w / 2, cy + 16, ui.text);
			ui.textCenter("Create one now, or turn on automatic backups.", x + w / 2, cy + 30, ui.text2);
			cy += 70;
		} else {
			int rowH = ui.lineH() * 2 + 14;
			ui.card(x, cy, w, list.size() * rowH + 6);
			int ry = cy + 3;
			for (BackupService.BackupInfo b : list) {
				ui.icon(Icons.ARCHIVE, x + 10, ry + 6, b.problem() != null ? ui.warning : ui.text2);
				ui.textClip(Fmt.dateTime(b.createdAt()) + " · " + (b.auto() ? "Automatic" : "Manual") + (b.label() != null && !b.label().isBlank() && !b.label().equals("automatic") && !b.label().equals("manual") ? " (" + b.label() + ")" : ""),
					x + 26, ry + 5, w - 250, ui.text);
				BackupService.VerifyResult vr = verified.get(b.file());
				String status = b.problem() != null ? b.problem() : vr == null ? Fmt.bytes(b.size()) + " · " + b.fileCount() + " files · schema " + b.schema()
					: vr.ok() ? "Verified · " + vr.sessionCount() + " sessions · " + vr.fileCount() + " files" : "Verification failed: " + vr.problems().getFirst();
				ui.textClip(status, x + 26, ry + 9 + ui.lineH(), w - 250, vr != null && !vr.ok() || b.problem() != null ? ui.warning : vr != null ? ui.success : ui.text3);
				int ax = x + w - 10;
				int dw = 56;
				ax -= dw;
				if (ui.button("set#bdel" + b.name(), ax, ry + (rowH - ui.buttonH()) / 2, dw, ui.buttonH(), "Delete", Ui.Btn.GHOST)) {
					screen.confirm("Delete backup?", "Deletes the archive " + b.name() + ". Your current records are not affected.", "Delete backup", true, () -> {
						siq.storage.submitIo(() -> java.nio.file.Files.deleteIfExists(b.file())).whenCompleteAsync((ok, e) -> {
							backupsRevision = -1;
							usage.invalidate();
							screen.toast(e == null ? "Backup deleted" : "Could not delete: " + cause(e), e == null ? SiqScreen.TOAST_SUCCESS : SiqScreen.TOAST_ERROR);
						}, SessionIQ.mainThread());
					});
				}
				ax -= 66;
				if (ui.button("set#brestore" + b.name(), ax, ry + (rowH - ui.buttonH()) / 2, 62, ui.buttonH(), "Restore…", Ui.Btn.SECONDARY)) {
					startRestore(b);
				}
				ax -= 60;
				if (ui.button("set#bverify" + b.name(), ax, ry + (rowH - ui.buttonH()) / 2, 56, ui.buttonH(), "Verify", Ui.Btn.GHOST)) {
					siq.storage.submitQuery(() -> siq.backups.verify(b.file())).whenCompleteAsync((res, e) -> {
						if (res != null) {
							verified.put(b.file(), res);
						}
					}, SessionIQ.mainThread());
				}
				ry += rowH;
			}
			cy += list.size() * rowH + 16;
		}

		cy += section(ui, x, cy, w, "Export");
		ui.text("Destination", x, cy + 2, ui.text2);
		cy += ui.lineH() + 4;
		TextField dest = ui.field("set#exportdir");
		if (!ui.isFocused("set#exportdir") && !dest.text.equals(c.exportDir)) {
			dest.set(c.exportDir);
		}
		dest.maxLength = 400;
		int chooseW = ui.buttonWidth("Choose…", Icons.FOLDER);
		ui.textField("set#exportdir", x, cy, w - chooseW - 72, 20, siq.paths.exports.toString() + " (default)", null);
		if (dest.consumeChanged() || dest.consumeSubmitted()) {
			c.exportDir = dest.text.trim();
			changed();
		}
		if (ui.button("set#choosedir", x + w - chooseW - 66, cy + 1, chooseW, ui.buttonH(), "Choose…", Icons.FOLDER, Ui.Btn.SECONDARY, true)) {
			FolderPicker.pick("Export destination", siq.exports.destination(), p -> {
				c.exportDir = p.toString();
				dest.set(c.exportDir);
				changed();
			}, err -> screen.toast(err, SiqScreen.TOAST_ERROR));
		}
		if (ui.button("set#defaultdir", x + w - 60, cy + 1, 60, ui.buttonH(), "Default", Ui.Btn.GHOST)) {
			c.exportDir = "";
			dest.set("");
			changed();
		}
		cy += 28;
		int ew = ui.buttonWidth("Export history…", Icons.EXPORT);
		if (ui.button("set#export2", x, cy, ew, ui.buttonH(), "Export history…", Icons.EXPORT, Ui.Btn.SECONDARY, siq.isReady())) {
			openExportAll();
		}
		cy += ui.buttonH() + 10;
		return cy - y;
	}

	private static String cause(Throwable e) {
		Throwable t = e.getCause() != null ? e.getCause() : e;
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}

	private void startRestore(BackupService.BackupInfo b) {
		screen.toast("Verifying " + b.name() + "…", SiqScreen.TOAST_INFO);
		siq.storage.submitQuery(() -> siq.backups.verify(b.file())).whenCompleteAsync((res, e) -> {
			if (res == null) {
				screen.toast("Could not verify the backup: " + cause(e), SiqScreen.TOAST_ERROR);
				return;
			}
			verified.put(b.file(), res);
			if (!res.ok()) {
				screen.openModal(new MessageModal("Backup cannot be restored", "This backup failed verification, so it will not be used. Your current records were not changed.",
					res.problems(), true));
				return;
			}
			screen.openModal(new RestoreModal(b, res));
		}, SessionIQ.mainThread());
	}

	private final class RestoreModal extends Modal {
		private final BackupService.BackupInfo info;
		private final BackupService.VerifyResult result;
		private int mode = 1;
		private boolean running;

		RestoreModal(BackupService.BackupInfo info, BackupService.VerifyResult result) {
			this.info = info;
			this.result = result;
		}

		@Override
		public String title() {
			return "Restore backup";
		}

		@Override
		public int preferredWidth(Ui ui) {
			return 380;
		}

		private String explanation() {
			return mode == 0
				? "Replace: all current sessions, chat, samples, nicknames and groups are removed and replaced by the backup's. The session being recorded now is kept. A safety backup of your current data is created first."
				: "Merge: sessions from the backup that you no longer have are added back. Existing records are kept unchanged. A safety backup is created first.";
		}

		@Override
		public int bodyHeight(Ui ui, int w) {
			return 10 + ui.lineH() * 2 + 14 + ui.buttonH() + 12 + ui.paragraphHeight(explanation(), w) + 20;
		}

		@Override
		public void renderBody(Ui ui, int x, int y, int w) {
			int cy = y + 6;
			ui.icon(Icons.CHECK, x, cy, ui.success);
			ui.text("Verified: " + result.sessionCount() + " sessions, " + result.fileCount() + " files, schema " + result.schema(), x + 14, cy, ui.text);
			cy += ui.lineH() + 4;
			ui.text("Created " + Fmt.dateTime(info.createdAt()), x + 14, cy, ui.text2);
			cy += ui.lineH() + 12;
			int m = ui.segmented("restore#mode", x, cy, new String[]{"Replace current records", "Merge into current records"}, mode);
			if (m >= 0) {
				mode = m;
			}
			cy += ui.buttonH() + 10;
			ui.paragraph(explanation(), x, cy, w, mode == 0 ? ui.warning : ui.text2);
		}

		@Override
		public void renderButtons(Ui ui, int x, int y, int w, int h) {
			String label = running ? "Restoring…" : mode == 0 ? "Replace with backup" : "Merge backup";
			int bw = ui.buttonWidth(label, null);
			if (ui.button("restore#go", x + w - bw, y, bw, h, label, null, mode == 0 ? Ui.Btn.DANGER : Ui.Btn.PRIMARY, !running)) {
				running = true;
				siq.restore(info.file(), mode == 0 ? BackupService.RestoreMode.REPLACE : BackupService.RestoreMode.MERGE).whenCompleteAsync((r, e) -> {
					running = false;
					close();
					backupsRevision = -1;
					usage.invalidate();
					ServerNames.invalidate();
					if (e != null) {
						screen.toast("Restore failed: " + cause(e) + ". A safety backup was made before any change.", SiqScreen.TOAST_ERROR);
					} else {
						screen.toast("Restored " + r.sessionsRestored() + " session(s)" + (r.sessionsSkipped() > 0 ? ", kept " + r.sessionsSkipped() + " existing" : "")
							+ ". Safety backup: " + r.safetyBackup(), SiqScreen.TOAST_SUCCESS);
					}
				}, SessionIQ.mainThread());
			}
			if (ui.button("restore#cancel", x + w - bw - 66, y, 60, h, "Cancel", Ui.Btn.SECONDARY)) {
				close();
			}
		}
	}

	private int advanced(Ui ui, int x, int y, int w) {
		int cy = y;
		SiqConfig c = cfg();
		cy += section(ui, x, cy, w, "Diagnostics");
		cy += toggleRow(ui, "set#diag", x, cy, w, "Diagnostic logging", "Writes extra SessionIQ details to the Minecraft log. Chat content is never logged.", c.diagnosticLogging, () -> {
			c.diagnosticLogging = !c.diagnosticLogging;
			changed();
		});
		int rh = labelRow(ui, x, cy, w, "Checkpoint interval", "How often the active session is saved. Shorter means less data lost if Minecraft crashes.", 170);
		float v = ui.slider("set#checkpoint", x + w - 170, cy + 4, 130, c.checkpointSeconds, 10, 120, 5);
		ui.textRight((int) v + " s", x + w, cy + 4, ui.text2);
		if ((int) v != c.checkpointSeconds) {
			c.checkpointSeconds = (int) v;
			if (!ui.mouseDown) {
				changed();
			}
		}
		cy += rh;

		cy += section(ui, x, cy, w, "Storage integrity");
		int iw = ui.buttonWidth(integrity.loading() ? "Checking…" : "Run integrity check", Icons.REFRESH);
		if (ui.button("set#integrity", x, cy, iw, ui.buttonH(), integrity.loading() ? "Checking…" : "Run integrity check", Icons.REFRESH, Ui.Btn.SECONDARY,
			!integrity.loading() && siq.isReady())) {
			siq.chat.flush();
			List<SessionRecord> snap = siq.sessions.snapshot();
			integrity.reload(System.nanoTime(), () -> siq.storage.submitIo(() -> null).thenCompose(n -> siq.storage.submitQuery(() -> IntegrityChecker.check(siq.paths, snap))));
		}
		cy += ui.buttonH() + 8;
		IntegrityChecker.Report rep = integrity.get();
		if (rep != null) {
			String summary = rep.sessionFiles + " session files · " + rep.chatPartitions + " chat partitions (" + Fmt.count(rep.chatMessages) + " messages) · "
				+ rep.sampleFiles + " sample files · " + rep.quarantined + " in quarantine";
			cy += ui.paragraph(summary, x, cy, w, ui.text2) + 4;
			if (rep.healthy()) {
				ui.icon(Icons.CHECK, x, cy, ui.success);
				ui.text("No problems found.", x + 14, cy, ui.success);
				cy += ui.lineH() + 8;
			} else {
				for (String p : rep.problems.stream().limit(30).toList()) {
					ui.fill(x + 2, cy + 3, 3, 3, ui.warning);
					cy += ui.paragraph(p, x + 10, cy, w - 10, ui.text2) + 2;
				}
				if (rep.problems.size() > 30) {
					ui.text("…and " + (rep.problems.size() - 30) + " more", x + 10, cy, ui.text3);
					cy += ui.lineH() + 4;
				}
				if (!rep.counterMismatches.isEmpty()) {
					ui.text(rep.counterMismatches.size() + " session(s) have chat counters that differ from the archive.", x, cy + 2, ui.text2);
					int fw = ui.buttonWidth("Fix counters", null);
					if (ui.button("set#fixcounters", x + w - fw, cy - 2, fw, ui.buttonH(), "Fix counters", Ui.Btn.SECONDARY)) {
						DataActions.applyChatCounts(siq, rep.counterMismatches);
						integrity.invalidate();
						screen.toast("Chat counters updated", SiqScreen.TOAST_SUCCESS);
					}
					cy += ui.buttonH() + 6;
				}
			}
		} else if (integrity.error() != null) {
			ui.text("Check failed: " + integrity.errorMessage(), x, cy, ui.error);
			cy += ui.lineH() + 8;
		}

		cy += section(ui, x, cy, w, "Status");
		Migrations.Status schema = siq.schemaStatus();
		var recovery = siq.recovery();
		String[][] facts = {
			{"Storage schema", schema == null ? "Loading…" : schema.schema() + (schema.newerThanSupported() ? " (newer than this version; opened read-compatibly)" : " (current)")},
			{"Recovery at last launch", recovery == null ? "Loading…" : recovery.recoveredIds().isEmpty() ? "Previous session closed normally" : recovery.recoveredIds().size() + " session(s) recovered"},
			{"Game hooks", HookCheck.missing().isEmpty() ? "All applied" : "Missing: " + String.join(", ", HookCheck.missing())},
			{"Failed writes this run", String.valueOf(siq.storage.failedWrites())},
			{"SessionIQ", siq.modVersion},
			{"Minecraft", siq.mcVersion},
			{"Data directory", siq.paths.root.toString()},
		};
		for (String[] f : facts) {
			ui.text(f[0], x, cy, ui.text2);
			int vh = ui.paragraph(f[1], x + 160, cy, w - 160, ui.text);
			cy += Math.max(ui.lineH() + 2, vh) + 4;
		}
		cy += 6;
		List<String> notes = new ArrayList<>();
		notes.addAll(siq.startupProblems());
		notes.addAll(siq.config.issuesSnapshot());
		notes.addAll(siq.themes.problems());
		for (StorageService.Issue i : siq.storage.issues()) {
			notes.add(Fmt.time(i.at()) + " · " + i.title() + ": " + i.detail());
		}
		if (!notes.isEmpty()) {
			cy += section(ui, x, cy, w, "Recent issues");
			int shown = 0;
			for (int i = notes.size() - 1; i >= 0 && shown < 25; i--, shown++) {
				ui.fill(x + 2, cy + 3, 3, 3, ui.warning);
				cy += ui.paragraph(notes.get(i), x + 10, cy, w - 10, ui.text2) + 2;
			}
			cy += 8;
		}
		cy += section(ui, x, cy, w, "Reset");
		int rw = ui.buttonWidth("Reset settings to defaults", null);
		if (ui.button("set#reset", x, cy, rw, ui.buttonH(), "Reset settings to defaults", Ui.Btn.DANGER)) {
			screen.confirm("Reset settings?", "All SessionIQ preferences return to their defaults. Recorded history, custom themes and backups are not affected.", "Reset settings", true,
				() -> {
					siq.config.resetToDefaults();
					siq.themes.select(cfg().theme);
					screen.toast("Settings reset", SiqScreen.TOAST_SUCCESS);
				});
		}
		cy += ui.buttonH() + 8;
		return cy - y;
	}

	private int credits(Ui ui, int x, int y, int w) {
		int cy = y;
		int logo = 40;
		ui.image(SiqScreen.LOGO, x, cy, logo, logo, SiqScreen.LOGO_SIZE, SiqScreen.LOGO_SIZE);
		ui.text("SessionIQ", x + logo + 12, cy + logo / 2 - ui.lineH() - 1, ui.text);
		ui.text("Version " + siq.modVersion, x + logo + 12, cy + logo / 2 + 3, ui.text2);
		cy += logo + 18;
		cy += section(ui, x, cy, w, "Created by");
		ui.text(CREATOR, x, cy, ui.text);
		ui.text("Main creator", x + 160, cy, ui.text2);
		cy += ui.lineH() + 14;
		cy += section(ui, x, cy, w, "Early feedback");
		for (String name : EARLY_FEEDBACK) {
			ui.text(name, x, cy, ui.text);
			cy += ui.lineH() + 6;
		}
		cy += 10;
		String note = "This mod was made with AI assistance.";
		ui.icon(Icons.INFO, x, cy, ui.text3);
		cy += ui.paragraph(note, x + 14, cy, w - 14, ui.text3) + 6;
		return cy - y;
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (!capturingKey) {
			return false;
		}
		capturingKey = false;
		if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
			return true;
		}
		KeyBinding key = SessionIQClient.openKey;
		key.setBoundKey(InputUtil.fromKeyCode(input));
		KeyBinding.updateKeysByCode();
		MinecraftClient.getInstance().options.write();
		screen.toast("SessionIQ now opens with " + key.getBoundKeyLocalizedText().getString(), SiqScreen.TOAST_SUCCESS);
		return true;
	}
}
