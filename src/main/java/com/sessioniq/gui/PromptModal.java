package com.sessioniq.gui;

import java.util.function.Consumer;
import java.util.function.Function;

public class PromptModal extends Modal {
	private final String title;
	private final String label;
	private final String confirmLabel;
	private final Function<String, String> validator;
	private final Consumer<String> onSubmit;
	private final String fieldId = "prompt#" + System.identityHashCode(this);
	private String error;
	private boolean focused;

	public PromptModal(String title, String label, String initial, String confirmLabel, int maxLength, Function<String, String> validator,
					   Consumer<String> onSubmit, Ui ui) {
		this.title = title;
		this.label = label;
		this.confirmLabel = confirmLabel;
		this.validator = validator;
		this.onSubmit = onSubmit;
		TextField f = ui.field(fieldId);
		f.set(initial == null ? "" : initial);
		f.maxLength = maxLength;
		f.anchor = 0;
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 300;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		return ui.lineH() + 6 + 20 + (error != null ? ui.paragraphHeight(error, w) + 4 : 0) + 12;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		if (!focused) {
			ui.focus(fieldId);
			focused = true;
		}
		ui.text(label, x, y + 6, ui.text2);
		TextField f = ui.textField(fieldId, x, y + ui.lineH() + 10, w, 20, "", null);
		if (f.consumeChanged()) {
			error = null;
		}
		if (f.consumeSubmitted()) {
			submit(f.text);
		}
		if (error != null) {
			ui.paragraph(error, x, y + ui.lineH() + 34, w, ui.error);
		}
	}

	private void submit(String value) {
		String problem = validator == null ? null : validator.apply(value);
		if (problem != null) {
			error = problem;
			return;
		}
		close();
		onSubmit.accept(value);
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		int cw = ui.buttonWidth(confirmLabel, null);
		int bw = ui.buttonWidth("Cancel", null);
		if (ui.button("prompt#ok", x + w - cw, y, cw, h, confirmLabel, Ui.Btn.PRIMARY)) {
			submit(ui.field(fieldId).text);
		}
		if (ui.button("prompt#cancel", x + w - cw - bw - 6, y, bw, h, "Cancel", Ui.Btn.SECONDARY)) {
			close();
		}
	}

	@Override
	public void close() {
		super.close();
	}
}
