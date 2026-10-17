package com.sessioniq.gui;

import java.util.List;

public class MessageModal extends Modal {
	private final String title;
	private final String message;
	private final List<String> details;
	private final boolean problem;

	public MessageModal(String title, String message, List<String> details, boolean problem) {
		this.title = title;
		this.message = message;
		this.details = details == null ? List.of() : details;
		this.problem = problem;
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 340;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		int h = ui.paragraphHeight(message, w) + 14;
		for (String d : details) {
			h += ui.paragraphHeight(d, w - 10) + 2;
		}
		return h + (details.isEmpty() ? 0 : 6);
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int cy = y + 6;
		cy += ui.paragraph(message, x, cy, w, problem ? ui.text : ui.text2) + 6;
		for (String d : details) {
			ui.fill(x + 1, cy + 3, 3, 3, problem ? ui.warning : ui.text3);
			cy += ui.paragraph(d, x + 10, cy, w - 10, ui.text2) + 2;
		}
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		if (ui.button("message#ok", x + w - 60, y, 60, h, "OK", Ui.Btn.PRIMARY)) {
			close();
		}
	}
}
