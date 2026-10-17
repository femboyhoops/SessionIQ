package com.sessioniq.gui;

import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;

public final class Widgets {
	private Widgets() {
	}

	public static int heading(Ui ui, int x, int y, int w, String title, String note) {
		ui.text(title, x, y, ui.text);
		if (note != null) {
			ui.textRight(ui.ellipsize(note, Math.max(0, w - ui.textW(title) - 20)), x + w, y, ui.text3);
		}
		return ui.lineH() + 8;
	}

	public static void kv(Ui ui, int x, int y, int w, String label, String value, int valueColor) {
		ui.text(label, x, y, ui.text2);
		ui.textRight(ui.ellipsize(value, w - ui.textW(label) - 12), x + w, y, valueColor);
	}

	public static int field(Ui ui, int x, int y, int w, String label, String value) {
		ui.text(label, x, y, ui.text3);
		int h = ui.paragraph(value == null ? "—" : value, x, y + ui.lineH() + 3, w, ui.text);
		return ui.lineH() + 3 + Math.max(h, ui.lineH() + 2) + 6;
	}

	public static int categoryColor(Ui ui, DisconnectCategory c) {
		return switch (c) {
			case INTENTIONAL, TRANSFER, CONNECTION_ABORTED -> ui.text2;
			case UNKNOWN, ABNORMAL_TERMINATION -> ui.warning;
			default -> ui.error;
		};
	}

	public static String certaintyLabel(DisconnectRecord d) {
		return switch (DisconnectRecord.Certainty.parse(d.certainty)) {
			case CONFIRMED -> "Confirmed";
			case INFERRED -> "Inferred";
			case UNKNOWN -> "Cause unknown";
		};
	}

	public static String reconnectSummary(DisconnectRecord d) {
		if (!d.isUnexpected() || d.visitId == null) {
			return d.reconnectAttempt ? "Reconnect attempt" : "—";
		}
		if (d.nextConnectionAt != null && d.timeToReconnectMs != null) {
			String where = Boolean.FALSE.equals(d.returnedToSameServer) ? " (other server)" : "";
			return "Back after " + Fmt.duration(d.timeToReconnectMs) + where;
		}
		return d.reconnectAttempts > 0 ? d.reconnectAttempts + " failed attempt(s)" : "Not reconnected";
	}

	public static int statusColor(Ui ui, SessionRecord s) {
		if (s.isActive()) {
			return ui.success;
		}
		return SessionRecord.STATUS_INTERRUPTED.equals(s.status) ? ui.warning : ui.text2;
	}

	public static String statusLabel(SessionRecord s) {
		if (s.isActive()) {
			return "Recording";
		}
		return SessionRecord.STATUS_INTERRUPTED.equals(s.status) ? "Recovered" : "Completed";
	}

	public static int[] columns(int x, int w, int... widths) {
		int[] out = new int[widths.length];
		int cx = x;
		for (int i = 0; i < widths.length; i++) {
			out[i] = cx;
			cx += widths[i];
		}
		return out;
	}

	public static boolean row(Ui ui, String id, int x, int y, int w, int h, boolean selected) {
		ui.hit(id, x, y, w, h);
		float hv = ui.hoverAnim(id);
		if (selected) {
			ui.fill(x, y, w, h, ui.selected);
		}
		if (hv > 0) {
			ui.fill(x, y, w, h, Ui.withAlpha(ui.hover, hv * 1.4f));
		}
		return ui.clicked(id);
	}
}
