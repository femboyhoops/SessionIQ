package com.sessioniq.gui;

import net.minecraft.client.input.KeyInput;

public abstract class Modal {
	boolean closed;
	long openedAt = System.currentTimeMillis();

	public abstract String title();

	public int preferredWidth(Ui ui) {
		return 300;
	}

	public abstract int bodyHeight(Ui ui, int w);

	public abstract void renderBody(Ui ui, int x, int y, int w);

	public abstract void renderButtons(Ui ui, int x, int y, int w, int h);

	public boolean keyPressed(KeyInput input, Ui ui) {
		return false;
	}

	public void close() {
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}
}
