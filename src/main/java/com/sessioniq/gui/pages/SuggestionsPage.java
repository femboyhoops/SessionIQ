package com.sessioniq.gui.pages;

import com.sessioniq.SessionIQ;
import com.sessioniq.gui.Fmt;
import com.sessioniq.gui.Icons;
import com.sessioniq.gui.OnlineGate;
import com.sessioniq.gui.Page;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.TextField;
import com.sessioniq.gui.Ui;
import com.sessioniq.online.ApiClient;
import com.sessioniq.online.OnlineService;

import java.util.ArrayList;
import java.util.List;

public class SuggestionsPage extends Page {
	private static final String FIELD = "suggest#text";
	private static final String[] CATEGORY_LABELS = {"Feature idea", "Bug report", "Other"};
	private static final String[] CATEGORIES = {"feature", "bug", "other"};
	private static final int MIN = 5;
	private static final int MAX = 1000;

	private record Sent(long at, String category, String text) {
	}

	private int category;
	private boolean includeName = true;
	private boolean sending;
	private String error;
	private final List<Sent> sent = new ArrayList<>();

	public SuggestionsPage(SiqScreen screen) {
		super(screen);
	}

	@Override
	public String title() {
		return "Suggestions";
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		int pad = ui.pad() + 4;
		int cx = x + pad;
		int cw = Math.min(w - pad * 2, 640);
		OnlineService.Status st = siq.online.status();
		if (st == OnlineService.Status.UNAVAILABLE || st == OnlineService.Status.DISABLED) {
			OnlineGate.blocked(ui, screen, "suggest#gate", x + pad, y + pad, w - pad * 2, h - pad * 2, false);
			return;
		}
		int off = ui.beginScroll("suggest#scroll", x, y, w, h);
		int cy = y + pad - off;
		ui.text("Send an idea or a problem straight to the SessionIQ developer.", cx, cy, ui.text);
		cy += ui.lineH() + 4;
		cy += ui.paragraph("Every suggestion is read. Describe what you would like, or what went wrong and what you were doing at the time.", cx, cy, cw, ui.text2) + 12;

		int sel = ui.segmented("suggest#cat", cx, cy, CATEGORY_LABELS, category);
		if (sel >= 0) {
			category = sel;
		}
		cy += ui.buttonH() + 10;

		TextField f = ui.field(FIELD);
		f.maxLength = MAX;
		f.filter = c -> c >= 32 && c != 127 && c != '§';
		ui.textField(FIELD, cx, cy, cw, 22, category == 1 ? "What happened, and what did you expect?" : "Your suggestion", null);
		if (f.consumeChanged()) {
			error = null;
		}
		boolean submit = f.consumeSubmitted();
		cy += 26;
		String count = f.text.length() + " / " + MAX;
		ui.textRight(count, cx + cw, cy, f.text.length() >= MAX ? ui.warning : ui.text3);
		if (f.text.length() > 60) {
			int ph = ui.paragraphHeight(f.text, cw - 24) + 16;
			cy += ui.lineH() + 6;
			ui.panel(cx, cy, cw, ph, Ui.argb(Ui.mix(ui.th.surface, ui.th.bg, 0.4f), 1f), ui.border, ui.radius());
			ui.paragraph(f.text, cx + 12, cy + 8, cw - 24, ui.text2);
			cy += ph + 6;
		} else {
			cy += ui.lineH() + 6;
		}

		boolean canName = siq.online.signedIn();
		if (canName) {
			if (ui.checkbox("suggest#name", cx, cy, includeName, "Include my username (" + siq.online.name() + ") so the developer can follow up")) {
				includeName = !includeName;
			}
			cy += ui.lineH() + 10;
		}
		boolean valid = f.text.trim().length() >= MIN;
		int bw = ui.buttonWidth(sending ? "Sending…" : "Send suggestion", Icons.SEND);
		if (ui.button("suggest#send", cx, cy, bw, ui.buttonH(), sending ? "Sending…" : "Send suggestion", Icons.SEND, Ui.Btn.PRIMARY, valid && !sending) || submit && valid && !sending) {
			send(f, canName && includeName);
		}
		if (!valid && !f.text.isEmpty()) {
			ui.textMid("At least " + MIN + " characters", cx + bw + 10, cy, ui.buttonH(), ui.text3);
		}
		cy += ui.buttonH() + 8;
		if (error != null) {
			cy += ui.paragraph(error, cx, cy, cw, ui.error) + 6;
		}
		cy += 8;
		String note = "A suggestion contains its text, the category and your SessionIQ version" + (canName ? ", plus your username if the box is ticked." : ".")
			+ " It is linked to your anonymous install ID only to limit sending to 5 a day.";
		cy += ui.paragraph(note, cx, cy, cw, ui.text3) + 14;

		if (!sent.isEmpty()) {
			ui.text("SENT THIS SESSION", cx, cy, ui.text3);
			cy += ui.lineH() + 8;
			for (int i = sent.size() - 1; i >= 0; i--) {
				Sent s = sent.get(i);
				int ph = ui.paragraphHeight(s.text(), cw - 24);
				int bh = ph + ui.lineH() + 22;
				ui.card(cx, cy, cw, bh);
				ui.icon(Icons.CHECK, cx + 12, cy + 9, ui.success);
				ui.text(s.category() + " · " + Fmt.time(s.at()), cx + 26, cy + 9, ui.text2);
				ui.paragraph(s.text(), cx + 12, cy + ui.lineH() + 16, cw - 24, ui.text);
				cy += bh + ui.gap();
			}
		}
		ui.endScroll("suggest#scroll", cy + off - y + pad);
	}

	private void send(TextField f, boolean withName) {
		String text = f.text.trim();
		String cat = CATEGORIES[category];
		String label = CATEGORY_LABELS[category];
		sending = true;
		error = null;
		siq.online.suggest(cat, text, withName).whenCompleteAsync((r, e) -> {
			sending = false;
			if (e != null) {
				error = ApiClient.message(e);
				return;
			}
			sent.add(new Sent(System.currentTimeMillis(), label, text));
			f.set("");
			screen.toast("Thanks! Your suggestion was sent.", SiqScreen.TOAST_SUCCESS);
		}, SessionIQ.mainThread());
	}
}
