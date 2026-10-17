package com.sessioniq.gui.pages;

import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.ServerNames;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.Ui;
import com.sessioniq.gui.Widgets;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.DataActions;

public class DisconnectDetailPage extends Page {
	private final String sessionId;
	private final String disconnectId;

	public DisconnectDetailPage(SiqScreen screen, String sessionId, String disconnectId) {
		super(screen);
		this.sessionId = sessionId;
		this.disconnectId = disconnectId;
	}

	private DisconnectRecord record() {
		SessionRecord s = siq.sessions.get(sessionId);
		return s == null ? null : s.disconnect(disconnectId);
	}

	@Override
	public String title() {
		DisconnectRecord d = record();
		return d == null ? "Disconnect" : d.categoryEnum().label + " · " + ServerNames.name(d.serverKey);
	}

	@Override
	public String crumb() {
		return "Event";
	}

	@Override
	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		DisconnectRecord d = record();
		if (d == null) {
			return right;
		}
		int x = right;
		int dw = ui.buttonWidth("Delete", Icons.TRASH);
		x -= dw;
		if (ui.button("dd#delete", x, y, dw, h, "Delete", Icons.TRASH, Ui.Btn.DANGER, true)) {
			screen.confirm("Delete this disconnect event?",
				"Only this event record is removed. The visit it ended stays in the session; disconnect counts and statistics update accordingly.",
				"Delete event", true, () -> {
					DataActions.deleteDisconnect(siq, sessionId, disconnectId);
					screen.back();
					screen.toast("Event deleted", SiqScreen.TOAST_SUCCESS);
				});
		}
		x -= 6;
		int cw = ui.buttonWidth("Copy diagnostics", Icons.COPY);
		x -= cw;
		if (ui.button("dd#copy", x, y, cw, h, "Copy diagnostics", Icons.COPY, Ui.Btn.SECONDARY, true)) {
			ui.copyToClipboard(diagnostics(d));
			screen.toast("Diagnostics copied (no chat content included)", SiqScreen.TOAST_INFO);
		}
		return x - 6;
	}

	private String diagnostics(DisconnectRecord d) {
		StringBuilder sb = new StringBuilder();
		sb.append("SessionIQ disconnect event ").append(d.id).append('\n');
		sb.append("Time: ").append(Fmt.dateTimeSeconds(d.at)).append('\n');
		sb.append("Server: ").append(ServerNames.name(d.serverKey)).append(" (").append(ServerNames.maskedAddress(d.address)).append(")\n");
		sb.append("Category: ").append(d.categoryEnum().label).append(" [").append(d.certainty).append("]\n");
		sb.append("Phase: ").append(d.phase).append('\n');
		sb.append("Reason: ").append(d.reason == null ? "(none available)" : d.reason).append('\n');
		if (d.reasonKey != null) {
			sb.append("Reason key: ").append(d.reasonKey).append('\n');
		}
		if (d.details != null) {
			sb.append("Details: ").append(d.details).append('\n');
		}
		for (String e : d.evidence) {
			sb.append("Evidence: ").append(e).append('\n');
		}
		sb.append("Reconnection: ").append(Widgets.reconnectSummary(d)).append('\n');
		return sb.toString();
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		DisconnectRecord d = record();
		SessionRecord s = siq.sessions.get(sessionId);
		if (d == null) {
			if (ui.emptyState("dd#missing", x, y, w, h, Icons.DISCONNECTS, "Event not found", "This event no longer exists. It may have been deleted.", "Back")) {
				screen.back();
			}
			return;
		}
		int pad = ui.pad() + 4;
		int off = ui.beginScroll("dd#scroll", x, y, w, h);
		int cx = x + pad;
		int cw = w - pad * 2;
		int cy = y + pad - off;
		int color = Widgets.categoryColor(ui, d.categoryEnum());

		String explanation = switch (DisconnectRecord.Certainty.parse(d.certainty)) {
			case CONFIRMED -> "The client directly observed what happened.";
			case INFERRED -> "Derived from indirect but specific evidence; the exact cause was not reported.";
			case UNKNOWN -> "There was not enough evidence to determine the cause.";
		};
		int ch = 18 + ui.lineH() * 2 + 18;
		ui.card(cx, cy, cw, ch);
		ui.fill(cx, cy + 6, 2, ch - 12, color);
		ui.icon(d.isUnexpected() ? Icons.WARNING : Icons.LEAVE, cx + 12, cy + 12, color);
		ui.text(d.categoryEnum().label, cx + 28, cy + 12, ui.text);
		ui.badge(cx + 28 + ui.textW(d.categoryEnum().label) + 8, cy + 10, Widgets.certaintyLabel(d), color);
		ui.textClip(explanation, cx + 28, cy + 18 + ui.lineH(), cw - 40, ui.text2);
		ui.textRight(Fmt.dateTimeSeconds(d.at), cx + cw - 12, cy + 12, ui.text3);
		cy += ch + ui.gap();

		boolean two = cw >= 520;
		int colW = two ? (cw - ui.gap()) / 2 : cw;
		VisitRecord v = s == null ? null : s.visit(d.visitId);
		int fy = cy + 10;
		int fw = colW - 24;
		int left = cx + 12;
		int h1 = 0;
		h1 += Widgets.field(ui, left, fy + h1, fw, "Reason shown by the game", d.reason == null ? "No reason was available" : d.reason);
		h1 += Widgets.field(ui, left, fy + h1, fw, "Reason key", d.reasonKey == null ? "—" : d.reasonKey);
		h1 += Widgets.field(ui, left, fy + h1, fw, "Diagnostic details", d.details == null ? "—" : d.details);
		h1 += Widgets.field(ui, left, fy + h1, fw, "Connection phase", switch (d.phase == null ? "" : d.phase) {
			case "connect" -> "Connecting (before login)";
			case "login" -> "Logging in";
			case "configuration" -> "Configuration";
			case "play" -> "Playing";
			default -> "Unknown";
		});
		int rx = two ? cx + colW + ui.gap() + 12 : left;
		int ry = two ? fy : fy + h1;
		int h2 = 0;
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Server", ServerNames.name(d.serverKey) + " · " + ServerNames.maskedAddress(d.address));
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Visit", v == null ? (d.visitId == null ? "None (the connection was never established)" : "Deleted")
			: Fmt.dateTime(v.joinedAt) + " · lasted " + Fmt.duration(v.durationMs));
		h2 += Widgets.field(ui, rx, ry + h2, fw, "Reconnection", reconnectDetail(d));
		if (d.reportFile != null) {
			h2 += Widgets.field(ui, rx, ry + h2, fw, "Client report file", "debug/" + d.reportFile);
		}
		if (d.recovered) {
			h2 += Widgets.field(ui, rx, ry + h2, fw, "Recorded", "During crash recovery on the next launch");
		}
		int body = two ? Math.max(h1, h2) : h1 + h2;
		cy += body + 14;

		ui.text("EVIDENCE", cx, cy, ui.text3);
		cy += ui.lineH() + 6;
		int eh = 10;
		for (String e : d.evidence) {
			eh += ui.paragraphHeight(e, cw - 34) + 4;
		}
		ui.card(cx, cy, cw, Math.max(eh, 26));
		int ey = cy + 8;
		if (d.evidence.isEmpty()) {
			ui.text("No evidence was recorded.", cx + 12, ey, ui.text3);
		}
		for (String e : d.evidence) {
			ui.fill(cx + 13, ey + 3, 3, 3, ui.text3);
			ey += ui.paragraph(e, cx + 22, ey, cw - 34, ui.text2) + 4;
		}
		cy += Math.max(eh, 26) + 12;

		int lx = cx;
		if (s != null && ui.link("dd#session", "Open session", lx, cy, ui.accent)) {
			screen.push(new SessionDetailPage(screen, sessionId));
		}
		lx += ui.textW("Open session") + 16;
		if (d.serverKey != null && ui.link("dd#server", "Open server", lx, cy, ui.accent)) {
			screen.push(new ServerDetailPage(screen, d.serverKey));
		}
		cy += ui.lineH() + 8;
		ui.endScroll("dd#scroll", cy + off - y + pad);
	}

	private static String reconnectDetail(DisconnectRecord d) {
		if (d.visitId == null) {
			return d.reconnectAttempt ? "This was a failed attempt to reconnect after an unexpected disconnect" : "Not applicable";
		}
		if (!d.isUnexpected()) {
			return "Not tracked for intentional disconnects";
		}
		StringBuilder sb = new StringBuilder();
		if (d.nextConnectionAt != null) {
			sb.append("Connected again ").append(Fmt.dateTime(d.nextConnectionAt));
			if (d.timeToReconnectMs != null) {
				sb.append(" after ").append(Fmt.duration(d.timeToReconnectMs));
			}
			sb.append(Boolean.TRUE.equals(d.returnedToSameServer) ? " to the same server" : " to a different server");
		} else {
			sb.append("No later connection was recorded");
		}
		sb.append(" · ").append(d.reconnectAttempts).append(" attempt(s) in between");
		return sb.toString();
	}
}
