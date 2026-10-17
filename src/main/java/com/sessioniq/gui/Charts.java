package com.sessioniq.gui;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

public final class Charts {
	private static final long[] DURATION_STEPS = {60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L, 7_200_000L,
		10_800_000L, 14_400_000L, 21_600_000L, 43_200_000L, 86_400_000L};

	private Charts() {
	}

	public enum Axis { DURATION, COUNT }

	static long niceStep(long max, Axis axis, int maxTicks) {
		if (max <= 0) {
			return axis == Axis.DURATION ? 3_600_000L : 1;
		}
		if (axis == Axis.DURATION) {
			for (long s : DURATION_STEPS) {
				if (max / s < maxTicks) {
					return s;
				}
			}
			long day = 86_400_000L;
			return ((max / maxTicks) / day + 1) * day;
		}
		double raw = max / (double) maxTicks;
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double norm = raw / mag;
		double nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
		return Math.max(1, (long) Math.ceil(nice * mag));
	}

	private static String axisLabel(long v, Axis axis) {
		return axis == Axis.DURATION ? Fmt.durationShort(v) : Fmt.count(v);
	}

	public static int bars(Ui ui, String id, int x, int y, int w, int h, List<String> labels, long[][] values, int[] colors, Axis axis,
						   IntFunction<String> tooltip, String emptyMessage) {
		int n = values.length;
		long max = 0;
		for (long[] bar : values) {
			long sum = 0;
			for (long v : bar) {
				sum += v;
			}
			max = Math.max(max, sum);
		}
		if (n == 0 || max == 0) {
			empty(ui, x, y, w, h, emptyMessage);
			return -1;
		}
		long step = niceStep(max, axis, 4);
		long top = (max / step + (max % step == 0 ? 0 : 1)) * step;
		int labelW = 0;
		for (long v = 0; v <= top; v += step) {
			labelW = Math.max(labelW, ui.textW(axisLabel(v, axis)));
		}
		int plotX = x + labelW + 6;
		int plotW = w - labelW - 6;
		int plotY = y + 4;
		int plotH = h - ui.lineH() - 10;
		for (long v = 0; v <= top; v += step) {
			int gy = plotY + plotH - Math.round(plotH * (v / (float) top));
			ui.hline(plotX, gy, plotW, v == 0 ? ui.border : Ui.withAlpha(ui.border, 0.5f));
			ui.textRight(axisLabel(v, axis), plotX - 5, gy - ui.capH() / 2, ui.text3);
		}
		float slot = plotW / (float) n;
		int barW = Math.max(1, Math.min(28, Math.round(slot * 0.62f)));
		int hoverIndex = -1;
		int clicked = -1;
		for (int i = 0; i < n; i++) {
			int bx = Math.round(plotX + slot * i + (slot - barW) / 2f);
			String bid = id + "#" + i;
			ui.hit(bid, Math.round(plotX + slot * i), plotY, Math.max(1, Math.round(slot)), plotH);
			boolean hv = ui.hovered(bid);
			if (hv) {
				hoverIndex = i;
				ui.fill(Math.round(plotX + slot * i), plotY, Math.max(1, Math.round(slot)), plotH, ui.hover);
			}
			float grow = ui.anim(id + "#g", 1f, 8f);
			int base = plotY + plotH;
			for (int s = 0; s < values[i].length; s++) {
				long v = values[i][s];
				if (v <= 0) {
					continue;
				}
				int bh = Math.max(1, Math.round(plotH * (v / (float) top) * grow));
				int color = colors[s % colors.length];
				ui.fill(bx, base - bh, barW, bh, hv ? Ui.mixArgb(color, 0xFFFFFFFF, 0.12f) : color);
				base -= bh;
			}
			if (ui.clicked(bid)) {
				clicked = i;
			}
		}
		int maxLabelW = 0;
		for (String l : labels) {
			maxLabelW = Math.max(maxLabelW, ui.textW(l));
		}
		int every = Math.max(1, (int) Math.ceil((maxLabelW + 8) / Math.max(1f, slot)));
		for (int i = 0; i < n; i += every) {
			String l = labels.get(i);
			int lx = Math.round(plotX + slot * i + slot / 2f - ui.textW(l) / 2f);
			lx = Math.max(plotX - 2, Math.min(lx, plotX + plotW - ui.textW(l)));
			ui.text(l, lx, plotY + plotH + 5, ui.text3);
		}
		if (hoverIndex >= 0 && tooltip != null) {
			ui.tooltipNow(tooltip.apply(hoverIndex));
		}
		return clicked;
	}

	public static void lines(Ui ui, String id, int x, int y, int w, int h, List<String> labels, long[][] series, int[] colors, Axis axis,
							 IntFunction<String> tooltip, String emptyMessage) {
		int n = labels.size();
		long max = 0;
		for (long[] s : series) {
			for (long v : s) {
				max = Math.max(max, v);
			}
		}
		if (n == 0 || max == 0) {
			empty(ui, x, y, w, h, emptyMessage);
			return;
		}
		long step = niceStep(max, axis, 4);
		long top = (max / step + (max % step == 0 ? 0 : 1)) * step;
		int labelW = 0;
		for (long v = 0; v <= top; v += step) {
			labelW = Math.max(labelW, ui.textW(axisLabel(v, axis)));
		}
		int plotX = x + labelW + 6;
		int plotW = w - labelW - 10;
		int plotY = y + 4;
		int plotH = h - ui.lineH() - 10;
		for (long v = 0; v <= top; v += step) {
			int gy = plotY + plotH - Math.round(plotH * (v / (float) top));
			ui.hline(plotX, gy, plotW, v == 0 ? ui.border : Ui.withAlpha(ui.border, 0.5f));
			ui.textRight(axisLabel(v, axis), plotX - 5, gy - ui.capH() / 2, ui.text3);
		}
		float stepX = n == 1 ? 0 : plotW / (float) (n - 1);
		String hid = id + "#plot";
		ui.hit(hid, plotX - 4, plotY, plotW + 8, plotH);
		int hover = -1;
		if (ui.hovered(hid)) {
			hover = n == 1 ? 0 : Math.max(0, Math.min(n - 1, Math.round((ui.mx - plotX) / stepX)));
			int hx = Math.round(plotX + stepX * hover);
			ui.vline(hx, plotY, plotH, Ui.withAlpha(ui.text2, 0.4f));
		}
		for (int s = 0; s < series.length; s++) {
			int color = colors[s % colors.length];
			float px = 0;
			float py = 0;
			for (int i = 0; i < n; i++) {
				float cx = plotX + stepX * i + (n == 1 ? plotW / 2f : 0);
				float cy = plotY + plotH - plotH * (series[s][i] / (float) top);
				if (i > 0) {
					ui.line(px, py, cx, cy, color, Math.max(1, Math.round(ui.phys * 0.75f)));
				}
				if (n <= 45 || i == hover) {
					int r = i == hover ? 2 : 1;
					ui.fill(Math.round(cx) - r, Math.round(cy) - r, r * 2 + 1, r * 2 + 1, color);
				}
				px = cx;
				py = cy;
			}
		}
		int maxLabelW = 0;
		for (String l : labels) {
			maxLabelW = Math.max(maxLabelW, ui.textW(l));
		}
		int every = Math.max(1, (int) Math.ceil((maxLabelW + 10) / Math.max(1f, stepX)));
		for (int i = 0; i < n; i += every) {
			String l = labels.get(i);
			int lx = Math.round(plotX + stepX * i - ui.textW(l) / 2f);
			lx = Math.max(plotX - 2, Math.min(lx, plotX + plotW - ui.textW(l)));
			ui.text(l, lx, plotY + plotH + 5, ui.text3);
		}
		if (hover >= 0 && tooltip != null) {
			ui.tooltipNow(tooltip.apply(hover));
		}
	}

	public static int hbars(Ui ui, String id, int x, int y, int w, List<String> labels, long[] values, int color, LongFunction<String> fmt,
							int rowH, IntFunction<String> tooltip) {
		long max = 0;
		for (long v : values) {
			max = Math.max(max, v);
		}
		int labelW = Math.min(w / 3, 140);
		int valueW = 0;
		for (long v : values) {
			valueW = Math.max(valueW, ui.textW(fmt.apply(v)));
		}
		int barX = x + labelW + 8;
		int barMaxW = w - labelW - valueW - 20;
		int clicked = -1;
		float grow = ui.anim(id + "#g", 1f, 8f);
		for (int i = 0; i < labels.size(); i++) {
			int ry = y + i * rowH;
			String rid = id + "#" + i;
			ui.hit(rid, x, ry, w, rowH);
			float hv = ui.hoverAnim(rid);
			if (hv > 0) {
				ui.fill(x - 4, ry, w + 8, rowH, Ui.withAlpha(ui.hover, hv * 1.4f));
			}
			ui.textMid(ui.ellipsize(labels.get(i), labelW), x, ry, rowH, ui.text);
			int bw = max == 0 ? 0 : Math.max(2, Math.round(barMaxW * (values[i] / (float) max) * grow));
			int bh = Math.max(4, rowH / 3);
			ui.roundRect(barX, ry + (rowH - bh) / 2, Math.max(2, barMaxW), bh, Math.min(2, ui.th.radius), Ui.withAlpha(ui.border, 0.6f));
			ui.roundRect(barX, ry + (rowH - bh) / 2, bw, bh, Math.min(2, ui.th.radius), color);
			ui.textMid(fmt.apply(values[i]), barX + Math.max(2, barMaxW) + 8, ry, rowH, ui.text2);
			if (tooltip != null) {
				ui.tooltip(rid, tooltip.apply(i));
			}
			if (ui.clicked(rid)) {
				clicked = i;
			}
		}
		return clicked;
	}

	public static void empty(Ui ui, int x, int y, int w, int h, String message) {
		ui.hline(x + 4, y + h - ui.lineH() - 6, w - 8, ui.border);
		String m = message == null ? "No data in this period" : message;
		ui.textCenter(m, x + w / 2, y + h / 2 - ui.lineH() / 2 - 4, ui.text3);
	}

	public static int legend(Ui ui, int x, int y, String[] labels, int[] colors) {
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			ui.roundRect(cx, y + 2, 7, 7, Math.min(2, ui.th.radius), colors[i % colors.length]);
			ui.text(labels[i], cx + 11, y + 1, ui.text2);
			cx += 11 + ui.textW(labels[i]) + 12;
		}
		return cx - x;
	}

	public static void sparkline(Ui ui, int x, int y, int w, int h, double[] values, int color) {
		if (values.length < 2) {
			return;
		}
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (double v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double range = Math.max(1e-9, max - min);
		float stepX = w / (float) (values.length - 1);
		for (int i = 1; i < values.length; i++) {
			float x1 = x + stepX * (i - 1);
			float x2 = x + stepX * i;
			float y1 = (float) (y + h - h * (values[i - 1] - min) / range);
			float y2 = (float) (y + h - h * (values[i] - min) / range);
			ui.line(x1, y1, x2, y2, color, Math.max(1, Math.round(ui.phys * 0.75f)));
		}
	}
}
