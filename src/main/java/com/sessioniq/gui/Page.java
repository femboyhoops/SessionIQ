package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import net.minecraft.client.input.KeyInput;

public abstract class Page {
	protected final SiqScreen screen;
	protected final SessionIQ siq;

	protected Page(SiqScreen screen) {
		this.screen = screen;
		this.siq = SessionIQ.get();
	}

	public abstract String title();

	public String crumb() {
		return title();
	}

	public abstract void render(Ui ui, int x, int y, int w, int h);

	public int renderHeaderActions(Ui ui, int right, int y, int h) {
		return right;
	}

	public boolean keyPressed(KeyInput input, Ui ui) {
		return false;
	}

	public void onShow() {
	}
}
