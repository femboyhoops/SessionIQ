package com.sessioniq.gui;

import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntPredicate;

public final class TextField {
	public String text = "";
	public int cursor;
	public int anchor;
	int scrollPx;
	public int maxLength = 256;
	public IntPredicate filter = c -> c >= 32 && c != 127;
	public boolean changed;
	public boolean submitted;

	public void set(String value) {
		text = value == null ? "" : value;
		cursor = anchor = text.length();
		scrollPx = 0;
	}

	public boolean consumeChanged() {
		boolean c = changed;
		changed = false;
		return c;
	}

	public boolean consumeSubmitted() {
		boolean s = submitted;
		submitted = false;
		return s;
	}

	boolean hasSelection() {
		return cursor != anchor;
	}

	int selMin() {
		return Math.min(cursor, anchor);
	}

	int selMax() {
		return Math.max(cursor, anchor);
	}

	void selectAll() {
		anchor = 0;
		cursor = text.length();
	}

	void placeCursor(Ui ui, int localX, boolean extend) {
		cursor = indexAt(ui, localX + scrollPx);
		if (!extend) {
			anchor = cursor;
		}
	}

	void dragTo(Ui ui, int localX) {
		cursor = indexAt(ui, localX + scrollPx);
	}

	private int indexAt(Ui ui, int px) {
		if (px <= 0) {
			return 0;
		}
		for (int i = 1; i <= text.length(); i++) {
			int w = ui.textW(text.substring(0, i));
			if (w > px) {
				int prev = ui.textW(text.substring(0, i - 1));
				return px - prev < w - px ? i - 1 : i;
			}
		}
		return text.length();
	}

	void ensureVisible(Ui ui, int viewW) {
		int cx = ui.textW(text.substring(0, Math.min(cursor, text.length())));
		if (cx - scrollPx > viewW - 4) {
			scrollPx = cx - viewW + 4;
		} else if (cx - scrollPx < 0) {
			scrollPx = Math.max(0, cx - 8);
		}
		int total = ui.textW(text);
		if (total - scrollPx < viewW - 4) {
			scrollPx = Math.max(0, total - viewW + 4);
		}
	}

	private void replaceSelection(String insert, Ui ui) {
		int a = selMin();
		int b = selMax();
		String next = text.substring(0, a) + insert + text.substring(b);
		if (next.length() > maxLength) {
			int room = maxLength - (text.length() - (b - a));
			if (room <= 0) {
				return;
			}
			insert = insert.substring(0, Math.min(insert.length(), room));
			next = text.substring(0, a) + insert + text.substring(b);
		}
		text = next;
		cursor = anchor = a + insert.length();
		changed = true;
	}

	public boolean charTyped(CharInput input, Ui ui) {
		int cp = input.codepoint();
		if (!filter.test(cp)) {
			return true;
		}
		replaceSelection(new String(Character.toChars(cp)), ui);
		return true;
	}

	public boolean keyPressed(KeyInput input, Ui ui) {
		boolean shift = input.hasShift();
		boolean word = input.hasCtrlOrCmd();
		if (input.isSelectAll()) {
			selectAll();
			return true;
		}
		if (input.isCopy()) {
			if (hasSelection()) {
				ui.copyToClipboard(text.substring(selMin(), selMax()));
			}
			return true;
		}
		if (input.isCut()) {
			if (hasSelection()) {
				ui.copyToClipboard(text.substring(selMin(), selMax()));
				replaceSelection("", ui);
			}
			return true;
		}
		if (input.isPaste()) {
			String clip = ui.mc.keyboard.getClipboard();
			StringBuilder sb = new StringBuilder();
			clip.codePoints().filter(c -> c != '\n' && c != '\r').filter(filter).forEach(sb::appendCodePoint);
			replaceSelection(sb.toString(), ui);
			return true;
		}
		switch (input.key()) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (hasSelection()) {
					replaceSelection("", ui);
				} else if (cursor > 0) {
					int from = word ? wordLeft(cursor) : cursor - 1;
					text = text.substring(0, from) + text.substring(cursor);
					cursor = anchor = from;
					changed = true;
				}
				return true;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (hasSelection()) {
					replaceSelection("", ui);
				} else if (cursor < text.length()) {
					int to = word ? wordRight(cursor) : cursor + 1;
					text = text.substring(0, cursor) + text.substring(to);
					changed = true;
				}
				return true;
			}
			case GLFW.GLFW_KEY_LEFT -> {
				if (!shift && hasSelection()) {
					cursor = anchor = selMin();
				} else {
					cursor = word ? wordLeft(cursor) : Math.max(0, cursor - 1);
					if (!shift) {
						anchor = cursor;
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				if (!shift && hasSelection()) {
					cursor = anchor = selMax();
				} else {
					cursor = word ? wordRight(cursor) : Math.min(text.length(), cursor + 1);
					if (!shift) {
						anchor = cursor;
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				cursor = 0;
				if (!shift) {
					anchor = cursor;
				}
				return true;
			}
			case GLFW.GLFW_KEY_END -> {
				cursor = text.length();
				if (!shift) {
					anchor = cursor;
				}
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				submitted = true;
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private int wordLeft(int from) {
		int i = from;
		while (i > 0 && text.charAt(i - 1) == ' ') {
			i--;
		}
		while (i > 0 && text.charAt(i - 1) != ' ') {
			i--;
		}
		return i;
	}

	private int wordRight(int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) != ' ') {
			i++;
		}
		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}
		return i;
	}
}
