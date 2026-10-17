package com.sessioniq.gui;

import com.sessioniq.theme.Theme;

import java.util.function.IntConsumer;

public class ColorPickerModal extends Modal {
	private final String title;
	private final int original;
	private final IntConsumer onChange;
	private float hue;
	private float sat;
	private float val;
	private final String hexId = "color#hex" + System.identityHashCode(this);
	private boolean hexInit;
	private int lastRgb = -1;

	public ColorPickerModal(String title, int rgb, IntConsumer onChange) {
		this.title = title;
		this.original = rgb;
		this.onChange = onChange;
		float[] hsv = toHsv(rgb);
		hue = hsv[0];
		sat = hsv[1];
		val = hsv[2];
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public int preferredWidth(Ui ui) {
		return 260;
	}

	@Override
	public int bodyHeight(Ui ui, int w) {
		return 110 + 14 + 22 + 16;
	}

	@Override
	public void renderBody(Ui ui, int x, int y, int w) {
		int top = y + 6;
		int sq = 110;
		int hueX = x + sq + 10;
		int hueW = 14;
		int cols = 22;
		for (int i = 0; i < cols; i++) {
			int cx = x + i * sq / cols;
			int cw = x + (i + 1) * sq / cols - cx;
			float s = i / (float) (cols - 1);
			int topC = 0xFF000000 | hsvToRgb(hue, s, 1f);
			ui.ctx.fillGradient(cx, top, cx + cw, top + sq, Ui.withAlpha(topC, ui.alpha), Ui.withAlpha(0xFF000000, ui.alpha));
		}
		ui.outline(x, top, sq, sq, ui.border);
		String sqId = "color#sv";
		ui.hit(sqId, x, top, sq, sq);
		if (ui.clicked(sqId)) {
			dragging = 1;
		}
		String hueId = "color#hue";
		ui.hit(hueId, hueX, top, hueW, sq);
		if (ui.clicked(hueId)) {
			dragging = 2;
		}
		if (!ui.mouseDown) {
			dragging = 0;
		}
		if (dragging == 1) {
			sat = clamp((ui.mx - x) / (float) sq);
			val = 1f - clamp((ui.my - top) / (float) sq);
		} else if (dragging == 2) {
			hue = clamp((ui.my - top) / (float) sq);
		}
		int segments = 12;
		for (int i = 0; i < segments; i++) {
			int y1 = top + i * sq / segments;
			int y2 = top + (i + 1) * sq / segments;
			int c1 = 0xFF000000 | hsvToRgb(i / (float) segments, 1, 1);
			int c2 = 0xFF000000 | hsvToRgb((i + 1) / (float) segments, 1, 1);
			ui.ctx.fillGradient(hueX, y1, hueX + hueW, y2, Ui.withAlpha(c1, ui.alpha), Ui.withAlpha(c2, ui.alpha));
		}
		ui.outline(hueX, top, hueW, sq, ui.border);
		int markerY = top + Math.round(hue * sq);
		ui.fill(hueX - 2, markerY - 1, hueW + 4, 2, 0xFFFFFFFF);
		int mx = x + Math.round(sat * sq);
		int my = top + Math.round((1 - val) * sq);
		ui.outline(mx - 3, my - 3, 6, 6, 0xFFFFFFFF);
		ui.outline(mx - 4, my - 4, 8, 8, 0xFF000000);

		int rgb = hsvToRgb(hue, sat, val);
		int pvX = hueX + hueW + 10;
		int pvW = x + w - pvX;
		if (pvW > 20) {
			ui.text("New", pvX, top, ui.text3);
			ui.roundRect(pvX, top + ui.lineH() + 2, pvW, 28, Math.min(3, ui.th.radius), 0xFF000000 | rgb);
			ui.text("Current", pvX, top + ui.lineH() + 36, ui.text3);
			ui.roundRect(pvX, top + 2 * ui.lineH() + 38, pvW, 28, Math.min(3, ui.th.radius), 0xFF000000 | original);
		}

		int fy = top + sq + 12;
		ui.text("Hex", x, fy + 6, ui.text2);
		TextField hex = ui.field(hexId);
		hex.maxLength = 7;
		if (!hexInit || rgb != lastRgb && !ui.isFocused(hexId)) {
			hex.set(Theme.hex(rgb));
			hexInit = true;
		}
		ui.textField(hexId, x + 30, fy, 90, 20, "#RRGGBB", null);
		if (hex.consumeChanged()) {
			Integer parsed = Theme.parseHex(hex.text);
			if (parsed != null) {
				float[] hsv = toHsv(parsed);
				hue = hsv[0];
				sat = hsv[1];
				val = hsv[2];
				rgb = parsed;
			}
		}
		if (rgb != lastRgb) {
			lastRgb = rgb;
			onChange.accept(rgb);
		}
	}

	private int dragging;

	@Override
	public void renderButtons(Ui ui, int x, int y, int w, int h) {
		if (ui.button("color#ok", x + w - 60, y, 60, h, "Apply", Ui.Btn.PRIMARY)) {
			close();
		}
		if (ui.button("color#cancel", x + w - 126, y, 60, h, "Cancel", Ui.Btn.SECONDARY)) {
			onChange.accept(original);
			close();
		}
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(1, v));
	}

	static float[] toHsv(int rgb) {
		float r = ((rgb >> 16) & 255) / 255f;
		float g = ((rgb >> 8) & 255) / 255f;
		float b = (rgb & 255) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d == 0) {
			h = 0;
		} else if (max == r) {
			h = ((g - b) / d % 6 + 6) % 6 / 6f;
		} else if (max == g) {
			h = ((b - r) / d + 2) / 6f;
		} else {
			h = ((r - g) / d + 4) / 6f;
		}
		return new float[]{h, max == 0 ? 0 : d / max, max};
	}

	static int hsvToRgb(float h, float s, float v) {
		float hh = (h % 1f + 1f) % 1f * 6f;
		int i = (int) Math.floor(hh);
		float f = hh - i;
		float p = v * (1 - s);
		float q = v * (1 - s * f);
		float t = v * (1 - s * (1 - f));
		float r, g, b;
		switch (i % 6) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
	}
}
