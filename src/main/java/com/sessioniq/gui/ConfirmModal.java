package com.sessioniq.gui;

import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

public class ConfirmModal extends Modal {
	private final String title;
	private final String message;
	private final String confirmLabel;
	private final boolean danger;
	private final Runnable onConfirm;

	public ConfirmModal(String title, String message, String confirmLabel, boolean danger, Runnable onConfirm) {
		this.title = title;
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.danger = danger;
		this.onConfirm = onConfirm;
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 320;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		return ui.paragraphHeight(message, w - (danger ? 16 : 0)) + 14;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int tx = x;
		if (danger) {
			ui.icon(Icons.WARNING, x, y + 6, ui.error);
			tx += 16;
		}
		ui.paragraph(message, tx, y + 6, w - (tx - x), ui.text2);
	}

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		int cw = ui.buttonWidth(confirmLabel, null);
		int bw = ui.buttonWidth("Cancel", null);
		if (ui.button("confirm#ok", x + w - cw, y, cw, h, confirmLabel, danger ? Ui.Btn.DANGER : Ui.Btn.PRIMARY)) {
			close();
			onConfirm.run();
		}
		if (ui.button("confirm#cancel", x + w - cw - bw - 6, y, bw, h, "Cancel", Ui.Btn.SECONDARY)) {
			close();
		}
	}

	@Override
	public boolean keyPressed(KeyInput input, Ui ui) {
		if (!danger && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			close();
			onConfirm.run();
			return true;
		}
		return false;
	}
}
