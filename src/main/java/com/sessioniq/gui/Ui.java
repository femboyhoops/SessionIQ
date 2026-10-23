package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.theme.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Ui {
	public static final int L_BASE = 0;
	public static final int L_POPUP = 1;
	public static final int L_MODAL = 2;
	public static final int L_TOP = 3;

	public final MinecraftClient mc = MinecraftClient.getInstance();
	public DrawContext ctx;
	public TextRenderer tr;
	public Theme th;
	public float scale = 1f;
	public float fontScale = 1f;
	public float phys = 2f;
	public int width;
	public int height;
	public int mx;
	public int my;
	public boolean mouseDown;
	public float dt;
	public float animIntensity = 1f;
	public boolean compact;
	public float alpha = 1f;
	public long frameMs;

	public int bg;
	public int bg2;
	public int surface;
	public int surface2;
	public int border;
	public int text;
	public int text2;
	public int text3;
	public int accent;
	public int onAccent;
	public int success;
	public int warning;
	public int error;
	public int hover;
	public int selected;
	public int[] chart = new int[6];

	private int layer = L_BASE;
	private int seq;
	private List<Hit> hits = new ArrayList<>();
	private List<Hit> prevHits = new ArrayList<>();
	private final List<Hit> scrollRegions = new ArrayList<>();
	private List<Hit> prevScrollRegions = new ArrayList<>();
	private final Deque<int[]> clips = new ArrayDeque<>();
	private String hoverId;
	private String clickedId;
	private int clickButton;
	private boolean doubleClick;
	private String pressedId;
	private final Map<String, Float> anims = new HashMap<>();
	private final Map<String, Long> hoverSince = new HashMap<>();
	final Map<String, TextField> fields = new HashMap<>();
	private final Map<String, Scroll> scrolls = new HashMap<>();
	private final List<String> fieldOrder = new ArrayList<>();
	private String focusId;
	private String tooltip;
	private int tooltipMaxWidth;
	private String openPopup;
	private final List<Deferred> deferred = new ArrayList<>();
	private final Map<String, Integer> popupResult = new HashMap<>();
	private long lastFrameNanos;
	private String dragId;
	private int dragAnchor;
	private float dragAnchorValue;
	public String cursorHint;

	record Hit(String id, int x, int y, int w, int h, int layer, int seq) {
		boolean contains(int px, int py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}
	}

	public void begin(DrawContext context, int screenW, int screenH, double mouseX, double mouseY) {
		SiqConfig c = SessionIQ.get().config.get();
		ctx = context;
		tr = mc.textRenderer;
		th = SessionIQ.get().themes.active();
		fontScale = c.fontScale;
		compact = c.compact;
		animIntensity = c.animation;
		int fbW = Math.max(1, mc.getWindow().getFramebufferWidth());
		int fbH = Math.max(1, mc.getWindow().getFramebufferHeight());
		int target = Math.max(1, Math.round(fbH / 360f * c.uiScale));
		target = Math.max(1, Math.min(target, fbW / 400));
		phys = target;
		scale = target / (float) mc.getWindow().getScaleFactor();
		width = Math.round(screenW / scale);
		height = Math.round(screenH / scale);
		mx = (int) Math.floor(mouseX / scale);
		my = (int) Math.floor(mouseY / scale);
		long now = System.nanoTime();
		dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		frameMs = System.currentTimeMillis();
		buildPalette();
		prevHits = hits;
		hits = new ArrayList<>(prevHits.size() + 16);
		prevScrollRegions = new ArrayList<>(scrollRegions);
		scrollRegions.clear();
		seq = 0;
		layer = L_BASE;
		alpha = 1f;
		tooltip = null;
		cursorHint = null;
		fieldOrder.clear();
		hoverId = topHit(mx, my);
		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().scale(scale, scale);
	}

	public void end() {
		for (Deferred d : new ArrayList<>(deferred)) {
			layer = d.layer + 1;
			d.action.run();
		}
		deferred.clear();
		layer = L_TOP;
		if (tooltip != null) {
			drawTooltip();
		}
		ctx.getMatrices().popMatrix();
		clickedId = null;
		doubleClick = false;
	}

	private void buildPalette() {
		Theme t = th;
		float po = t.panelOpacity;
		bg = argb(t.bg, t.backdropOpacity);
		bg2 = argb(t.bg2, Math.min(1f, t.backdropOpacity + 0.03f));
		surface = argb(t.surface, po);
		surface2 = argb(t.surface2, po);
		border = argb(t.border, 1f);
		text = argb(t.text, 1f);
		text2 = argb(t.text2, 1f);
		text3 = argb(mix(t.text2, t.surface, 0.35f), 1f);
		accent = argb(t.accent, 1f);
		onAccent = luminance(t.accent) > 0.55 ? 0xFF111317 : 0xFFFFFFFF;
		success = argb(t.success, 1f);
		warning = argb(t.warning, 1f);
		error = argb(t.error, 1f);
		hover = argb(t.dark ? 0xFFFFFF : 0x000000, t.dark ? 0.045f : 0.05f);
		selected = argb(t.accent, t.dark ? 0.16f : 0.12f);
		for (int i = 0; i < 6; i++) {
			chart[i] = argb(t.chart[i], 1f);
		}
	}

	public static int argb(int rgb, float a) {
		int alpha = Math.max(0, Math.min(255, Math.round(a * 255)));
		return alpha << 24 | rgb & 0xFFFFFF;
	}

	public static int withAlpha(int argb, float a) {
		int base = argb >>> 24;
		return Math.round(base * Math.max(0, Math.min(1, a))) << 24 | argb & 0xFFFFFF;
	}

	public static int mix(int rgbA, int rgbB, float t) {
		int r = Math.round(((rgbA >> 16) & 255) * (1 - t) + ((rgbB >> 16) & 255) * t);
		int g = Math.round(((rgbA >> 8) & 255) * (1 - t) + ((rgbB >> 8) & 255) * t);
		int b = Math.round((rgbA & 255) * (1 - t) + (rgbB & 255) * t);
		return r << 16 | g << 8 | b;
	}

	public static int mixArgb(int a, int b, float t) {
		int alphaA = a >>> 24;
		int alphaB = b >>> 24;
		int al = Math.round(alphaA * (1 - t) + alphaB * t);
		return al << 24 | mix(a, b, t);
	}

	public static double luminance(int rgb) {
		return (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255.0;
	}

	private int c(int argb) {
		return alpha >= 1f ? argb : withAlpha(argb, alpha);
	}

	public void fill(int x, int y, int w, int h, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
			return;
		}
		ctx.fill(x, y, x + w, y + h, c(argb));
	}

	private void physFill(int x1, int y1, int x2, int y2, int argb) {
		if (x2 > x1 && y2 > y1) {
			ctx.fill(x1, y1, x2, y2, c(argb));
		}
	}

	private void pushPhys() {
		ctx.getMatrices().pushMatrix();
		float inv = 1f / phys;
		ctx.getMatrices().scale(inv, inv);
	}

	private void popPhys() {
		ctx.getMatrices().popMatrix();
	}

	private int px(float v) {
		return Math.round(v * phys);
	}

	public void outline(int x, int y, int w, int h, int argb) {
		pushPhys();
		int x1 = px(x), y1 = px(y), x2 = px(x + w), y2 = px(y + h);
		physFill(x1, y1, x2, y1 + 1, argb);
		physFill(x1, y2 - 1, x2, y2, argb);
		physFill(x1, y1 + 1, x1 + 1, y2 - 1, argb);
		physFill(x2 - 1, y1 + 1, x2, y2 - 1, argb);
		popPhys();
	}

	public void hline(int x, int y, int w, int argb) {
		pushPhys();
		int y1 = px(y);
		physFill(px(x), y1, px(x + w), y1 + 1, argb);
		popPhys();
	}

	public void vline(int x, int y, int h, int argb) {
		pushPhys();
		int x1 = px(x);
		physFill(x1, px(y), x1 + 1, px(y + h), argb);
		popPhys();
	}

	public void roundRect(int x, int y, int w, int h, int radius, int argb) {
		if (radius <= 0) {
			fill(x, y, w, h, argb);
			return;
		}
		pushPhys();
		int x1 = px(x), y1 = px(y), x2 = px(x + w), y2 = px(y + h);
		int r = Math.min(px(radius), Math.min((x2 - x1) / 2, (y2 - y1) / 2));
		int row = 0;
		while (row < r) {
			int inset = inset(r, row);
			int end = row + 1;
			while (end < r && inset(r, end) == inset) {
				end++;
			}
			physFill(x1 + inset, y1 + row, x2 - inset, y1 + end, argb);
			physFill(x1 + inset, y2 - end, x2 - inset, y2 - row, argb);
			row = end;
		}
		physFill(x1, y1 + r, x2, y2 - r, argb);
		popPhys();
	}

	private static int inset(int r, int row) {
		double dy = r - row - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * (double) r - dy * dy)));
	}

	public void roundBorder(int x, int y, int w, int h, int radius, int argb) {
		if (radius <= 0) {
			outline(x, y, w, h, argb);
			return;
		}
		pushPhys();
		int x1 = px(x), y1 = px(y), x2 = px(x + w), y2 = px(y + h);
		int r = Math.min(px(radius), Math.min((x2 - x1) / 2, (y2 - y1) / 2));
		int ri = Math.max(0, r - 1);
		for (int row = 0; row < r; row++) {
			int o = inset(r, row);
			int i = row == 0 ? (x2 - x1) / 2 : 1 + inset(ri, row - 1);
			if (row == 0) {
				physFill(x1 + o, y1, x2 - o, y1 + 1, argb);
				physFill(x1 + o, y2 - 1, x2 - o, y2, argb);
			} else {
				int inner = Math.max(o + 1, i);
				physFill(x1 + o, y1 + row, x1 + inner, y1 + row + 1, argb);
				physFill(x2 - inner, y1 + row, x2 - o, y1 + row + 1, argb);
				physFill(x1 + o, y2 - row - 1, x1 + inner, y2 - row, argb);
				physFill(x2 - inner, y2 - row - 1, x2 - o, y2 - row, argb);
			}
		}
		physFill(x1, y1 + r, x1 + 1, y2 - r, argb);
		physFill(x2 - 1, y1 + r, x2, y2 - r, argb);
		popPhys();
	}

	public void panel(int x, int y, int w, int h, int fillArgb, int borderArgb, int radius) {
		roundRect(x, y, w, h, radius, fillArgb);
		if (borderArgb != 0) {
			roundBorder(x, y, w, h, radius, borderArgb);
		}
	}

	public void card(int x, int y, int w, int h) {
		panel(x, y, w, h, surface, border, th.radius);
	}

	public int radius() {
		return th.radius;
	}

	public void line(float x1, float y1, float x2, float y2, int argb, int thickness) {
		pushPhys();
		int ax = Math.round(x1 * phys), ay = Math.round(y1 * phys), bx = Math.round(x2 * phys), by = Math.round(y2 * phys);
		int t = Math.max(1, thickness);
		int half = t / 2;
		int dx = Math.abs(bx - ax), dy = Math.abs(by - ay);
		if (dx >= dy) {
			if (ax > bx) {
				int tx = ax; ax = bx; bx = tx;
				int ty = ay; ay = by; by = ty;
			}
			int runStart = ax;
			int prevY = ay;
			for (int x = ax; x <= bx; x++) {
				int y = dx == 0 ? ay : Math.round(ay + (by - ay) * (float) (x - ax) / dx);
				if (y != prevY) {
					physFill(runStart, prevY - half, x, prevY - half + t, argb);
					runStart = x;
					prevY = y;
				}
			}
			physFill(runStart, prevY - half, bx + 1, prevY - half + t, argb);
		} else {
			if (ay > by) {
				int tx = ax; ax = bx; bx = tx;
				int ty = ay; ay = by; by = ty;
			}
			int runStart = ay;
			int prevX = ax;
			for (int y = ay; y <= by; y++) {
				int x = Math.round(ax + (bx - ax) * (float) (y - ay) / dy);
				if (x != prevX) {
					physFill(prevX - half, runStart, prevX - half + t, y, argb);
					runStart = y;
					prevX = x;
				}
			}
			physFill(prevX - half, runStart, prevX - half + t, by + 1, argb);
		}
		popPhys();
	}

	public void icon(Icons icon, int x, int y, int argb) {
		for (int[] run : icon.runs) {
			fill(x + run[1], y + run[0], run[2], 1, argb);
		}
	}

	public void image(Identifier texture, int x, int y, int w, int h, int texW, int texH) {
		pushPhys();
		int x1 = px(x), y1 = px(y);
		ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x1, y1, 0, 0, px(x + w) - x1, px(y + h) - y1, texW, texH, texW, texH, c(0xFFFFFFFF));
		popPhys();
	}

	public void dot(int cx, int cy, int r, int argb) {
		roundRect(cx - r, cy - r, r * 2, r * 2, r, argb);
	}

	public int lineH() {
		return Math.round(9 * fontScale);
	}

	public int capH() {
		return Math.round(8 * fontScale);
	}

	public int textW(String s) {
		return s == null ? 0 : Math.round(tr.getWidth(s) * fontScale);
	}

	public int textW(OrderedText s) {
		return Math.round(tr.getWidth(s) * fontScale);
	}

	public void text(String s, int x, int y, int argb) {
		if (s == null || s.isEmpty()) {
			return;
		}
		if (fontScale == 1f) {
			ctx.drawText(tr, s, x, y, c(argb), false);
		} else {
			ctx.getMatrices().pushMatrix();
			ctx.getMatrices().translate(x, y);
			ctx.getMatrices().scale(fontScale, fontScale);
			ctx.drawText(tr, s, 0, 0, c(argb), false);
			ctx.getMatrices().popMatrix();
		}
	}

	public void text(OrderedText s, int x, int y, int argb) {
		if (fontScale == 1f) {
			ctx.drawText(tr, s, x, y, c(argb), false);
		} else {
			ctx.getMatrices().pushMatrix();
			ctx.getMatrices().translate(x, y);
			ctx.getMatrices().scale(fontScale, fontScale);
			ctx.drawText(tr, s, 0, 0, c(argb), false);
			ctx.getMatrices().popMatrix();
		}
	}

	public void textRight(String s, int right, int y, int argb) {
		text(s, right - textW(s), y, argb);
	}

	public void textCenter(String s, int cx, int y, int argb) {
		text(s, cx - textW(s) / 2, y, argb);
	}

	public void textMid(String s, int x, int y, int h, int argb) {
		text(s, x, y + (h - capH()) / 2 + 1, argb);
	}

	public String ellipsize(String s, int maxW) {
		if (s == null) {
			return "";
		}
		if (textW(s) <= maxW) {
			return s;
		}
		int avail = Math.max(0, Math.round((maxW - textW("…")) / fontScale));
		return tr.trimToWidth(s, avail) + "…";
	}

	public void textClip(String s, int x, int y, int maxW, int argb) {
		text(ellipsize(s, maxW), x, y, argb);
	}

	public List<OrderedText> wrap(String s, int maxW) {
		return tr.wrapLines(StringVisitable.plain(s == null ? "" : s), Math.max(10, Math.round(maxW / fontScale)));
	}

	public List<OrderedText> wrap(Text t, int maxW) {
		return tr.wrapLines(t, Math.max(10, Math.round(maxW / fontScale)));
	}

	public int paragraph(String s, int x, int y, int maxW, int argb) {
		List<OrderedText> lines = wrap(s, maxW);
		int lh = lineH() + 2;
		for (int i = 0; i < lines.size(); i++) {
			text(lines.get(i), x, y + i * lh, argb);
		}
		return lines.size() * lh;
	}

	public int paragraphHeight(String s, int maxW) {
		return wrap(s, maxW).size() * (lineH() + 2);
	}

	public static Text styled(String s, int rgb) {
		return Text.literal(s).setStyle(Style.EMPTY.withColor(rgb & 0xFFFFFF));
	}

	public void hit(String id, int x, int y, int w, int h) {
		int[] clip = clips.peek();
		if (clip != null) {
			int cx = Math.max(x, clip[0]);
			int cy = Math.max(y, clip[1]);
			int cx2 = Math.min(x + w, clip[2]);
			int cy2 = Math.min(y + h, clip[3]);
			if (cx2 <= cx || cy2 <= cy) {
				return;
			}
			x = cx;
			y = cy;
			w = cx2 - cx;
			h = cy2 - cy;
		}
		hits.add(new Hit(id, x, y, w, h, layer, seq++));
	}

	private String topHit(int x, int y) {
		Hit best = null;
		for (Hit h : prevHits) {
			if (h.contains(x, y) && (best == null || h.layer > best.layer || h.layer == best.layer && h.seq > best.seq)) {
				best = h;
			}
		}
		return best == null ? null : best.id;
	}

	public boolean hovered(String id) {
		return id.equals(hoverId) && dragId == null || id.equals(dragId);
	}

	public boolean clicked(String id, int button) {
		if (id.equals(clickedId) && clickButton == button) {
			clickedId = null;
			return true;
		}
		return false;
	}

	public boolean clicked(String id) {
		return clicked(id, 0);
	}

	public boolean wasDoubleClick() {
		return doubleClick;
	}

	public boolean isPressed(String id) {
		return mouseDown && id.equals(pressedId);
	}

	public int layer() {
		return layer;
	}

	public void setLayer(int l) {
		layer = l;
	}

	public boolean onMouseDown(double rawX, double rawY, int button, boolean doubled) {
		int x = (int) Math.floor(rawX / scale);
		int y = (int) Math.floor(rawY / scale);
		mx = x;
		my = y;
		mouseDown = true;
		String id = topHit(x, y);
		if (openPopup != null && (id == null || !id.startsWith(openPopup))) {
			openPopup = null;
			pressedId = null;
			return true;
		}
		if (focusId != null && (id == null || !id.equals(focusId))) {
			focusId = null;
		}
		pressedId = id;
		clickedId = id;
		clickButton = button;
		doubleClick = doubled;
		return id != null;
	}

	public void onMouseUp() {
		mouseDown = false;
		pressedId = null;
		dragId = null;
	}

	public void onMouseMove(double rawX, double rawY) {
		mx = (int) Math.floor(rawX / scale);
		my = (int) Math.floor(rawY / scale);
	}

	public boolean onScroll(double rawX, double rawY, double amount) {
		int x = (int) Math.floor(rawX / scale);
		int y = (int) Math.floor(rawY / scale);
		if (openPopup != null) {
			Scroll s = scrolls.get(openPopup + "#list");
			if (s != null) {
				s.target -= (float) amount * 24;
			}
			return true;
		}
		Hit best = null;
		for (Hit h : prevScrollRegions) {
			if (h.contains(x, y) && (best == null || h.layer > best.layer || h.layer == best.layer && h.seq > best.seq)) {
				best = h;
			}
		}
		int topLayer = 0;
		for (Hit h : prevHits) {
			if (h.contains(x, y)) {
				topLayer = Math.max(topLayer, h.layer);
			}
		}
		if (best == null || best.layer < topLayer) {
			return false;
		}
		Scroll s = scrolls.get(best.id);
		if (s != null) {
			s.target -= (float) amount * Math.max(24, lineH() * 3);
			return true;
		}
		return false;
	}

	public float anim(String key, float target) {
		return anim(key, target, 14f);
	}

	public float anim(String key, float target, float speed) {
		if (animIntensity <= 0.01f) {
			anims.put(key, target);
			return target;
		}
		float cur = anims.getOrDefault(key, target);
		float k = 1f - (float) Math.exp(-speed * dt / Math.max(0.25f, animIntensity));
		cur += (target - cur) * k;
		if (Math.abs(target - cur) < 0.002f) {
			cur = target;
		}
		anims.put(key, cur);
		return cur;
	}

	public void resetAnim(String key, float value) {
		anims.put(key, value);
	}

	public float hoverAnim(String id) {
		return anim(id + "#h", hovered(id) ? 1f : 0f, 18f);
	}

	public void pushClip(int x, int y, int w, int h) {
		int[] parent = clips.peek();
		int[] r = {x, y, x + w, y + h};
		if (parent != null) {
			r[0] = Math.max(r[0], parent[0]);
			r[1] = Math.max(r[1], parent[1]);
			r[2] = Math.min(r[2], parent[2]);
			r[3] = Math.min(r[3], parent[3]);
		}
		r[2] = Math.max(r[0], r[2]);
		r[3] = Math.max(r[1], r[3]);
		clips.push(r);
		ctx.enableScissor(r[0], r[1], r[2], r[3]);
	}

	public void popClip() {
		clips.pop();
		ctx.disableScissor();
	}

	public boolean visible(int y, int h) {
		int[] clip = clips.peek();
		return clip == null || y + h > clip[1] && y < clip[3];
	}

	public void tooltip(String id, String textValue) {
		if (textValue == null || !hovered(id)) {
			hoverSince.remove(id);
			return;
		}
		long since = hoverSince.computeIfAbsent(id, k -> frameMs);
		if (frameMs - since > 350) {
			tooltip = textValue;
			tooltipMaxWidth = 220;
		}
	}

	public void tooltipNow(String textValue) {
		tooltip = textValue;
		tooltipMaxWidth = 240;
	}

	private void drawTooltip() {
		List<OrderedText> lines = new ArrayList<>();
		for (String part : tooltip.split("\n")) {
			lines.addAll(wrap(part, tooltipMaxWidth));
		}
		int w = 0;
		for (OrderedText l : lines) {
			w = Math.max(w, textW(l));
		}
		int lh = lineH() + 2;
		int h = lines.size() * lh + 8;
		w += 12;
		int x = mx + 10;
		int y = my + 12;
		if (x + w > width - 4) {
			x = mx - w - 6;
		}
		if (y + h > height - 4) {
			y = my - h - 6;
		}
		x = Math.max(4, x);
		y = Math.max(4, y);
		panel(x, y, w, h, argb(mix(th.surface2, th.dark ? 0x000000 : 0xFFFFFF, 0.15f), 0.98f), border, Math.min(3, th.radius));
		for (int i = 0; i < lines.size(); i++) {
			text(lines.get(i), x + 6, y + 5 + i * lh, i == 0 ? text : text2);
		}
	}

	public void defer(Runnable r) {
		deferred.add(new Deferred(layer, r));
	}

	private record Deferred(int layer, Runnable action) {
	}

	public enum Btn { PRIMARY, SECONDARY, GHOST, DANGER }

	public int buttonH() {
		return compact ? 16 : 18;
	}

	public int rowH() {
		return compact ? 18 : 22;
	}

	public int gap() {
		return compact ? 6 : 8;
	}

	public int pad() {
		return compact ? 8 : 12;
	}

	public int buttonWidth(String label, Icons icon) {
		return textW(label) + 16 + (icon != null ? 13 : 0);
	}

	public boolean button(String id, int x, int y, int w, int h, String label, Btn style) {
		return button(id, x, y, w, h, label, null, style, true);
	}

	public boolean button(String id, int x, int y, int w, int h, String label, Icons icon, Btn style, boolean enabled) {
		if (enabled) {
			hit(id, x, y, w, h);
		}
		float hv = enabled ? hoverAnim(id) : 0;
		boolean pressed = enabled && isPressed(id);
		int fillC;
		int borderC = 0;
		int textC;
		switch (style) {
			case PRIMARY -> {
				fillC = mixArgb(accent, argb(th.dark ? 0xFFFFFF : 0x000000, 1f), 0.1f * hv + (pressed ? 0.08f : 0));
				textC = onAccent;
			}
			case DANGER -> {
				fillC = argb(th.error, 0.14f + 0.1f * hv);
				borderC = argb(th.error, 0.5f);
				textC = error;
			}
			case GHOST -> {
				fillC = withAlpha(hover, hv * 1.4f);
				textC = mixArgb(text2, text, hv);
			}
			default -> {
				fillC = mixArgb(surface2, argb(th.dark ? 0xFFFFFF : 0x000000, 1f), 0.05f * hv + (pressed ? 0.04f : 0));
				borderC = border;
				textC = text;
			}
		}
		if (!enabled) {
			fillC = withAlpha(fillC, 0.45f);
			textC = withAlpha(text3, 0.9f);
		}
		int r = Math.min(th.radius, h / 2);
		panel(x, y, w, h, fillC, borderC, r);
		int contentW = textW(label) + (icon != null ? (label.isEmpty() ? 9 : 13) : 0);
		int cx = x + (w - contentW) / 2;
		if (icon != null) {
			icon(icon, cx, y + (h - 9) / 2, textC);
			cx += label.isEmpty() ? 9 : 13;
		}
		textMid(label, cx, y, h, textC);
		return enabled && clicked(id);
	}

	public boolean iconButton(String id, int x, int y, int size, Icons icon, String tip) {
		return iconButton(id, x, y, size, icon, tip, false);
	}

	public boolean iconButton(String id, int x, int y, int size, Icons icon, String tip, boolean active) {
		hit(id, x, y, size, size);
		float hv = hoverAnim(id);
		if (active) {
			roundRect(x, y, size, size, Math.min(th.radius, size / 2), selected);
		}
		if (hv > 0) {
			roundRect(x, y, size, size, Math.min(th.radius, size / 2), withAlpha(hover, hv * 1.5f));
		}
		icon(icon, x + (size - 9) / 2, y + (size - 9) / 2, active ? accent : mixArgb(text2, text, hv));
		tooltip(id, tip);
		return clicked(id);
	}

	public boolean link(String id, String label, int x, int y, int argb) {
		int w = textW(label);
		hit(id, x, y - 1, w, lineH() + 1);
		boolean h = hovered(id);
		text(label, x, y, h ? mixArgb(argb, text, 0.25f) : argb);
		if (h) {
			fill(x, y + lineH() - 1, w, 1, withAlpha(argb, 0.6f));
		}
		return clicked(id);
	}

	public boolean toggle(String id, int x, int y, boolean value) {
		int w = 24, h = 13;
		hit(id, x, y, w, h);
		float t = anim(id + "#t", value ? 1f : 0f, 16f);
		float hv = hoverAnim(id);
		int track = mixArgb(argb(th.dark ? mix(th.surface2, 0xFFFFFF, 0.08f) : mix(th.surface2, 0x000000, 0.12f), 1f), accent, t);
		roundRect(x, y, w, h, h / 2, track);
		if (hv > 0 && !value) {
			roundBorder(x, y, w, h, h / 2, withAlpha(border, 1f));
		}
		int knob = h - 4;
		int kx = Math.round(x + 2 + (w - knob - 4) * t);
		roundRect(kx, y + 2, knob, knob, knob / 2, value ? onAccent : argb(th.dark ? 0xD8DCE3 : 0xFFFFFF, 1f));
		return clicked(id);
	}

	public boolean checkbox(String id, int x, int y, boolean value, String label) {
		int size = 11;
		int w = size + (label == null ? 0 : 6 + textW(label));
		hit(id, x, y - 1, w, size + 2);
		float hv = hoverAnim(id);
		if (value) {
			roundRect(x, y, size, size, Math.min(2, th.radius), accent);
			icon(Icons.CHECK, x + 2, y + 1, onAccent);
		} else {
			roundRect(x, y, size, size, Math.min(2, th.radius), surface2);
			roundBorder(x, y, size, size, Math.min(2, th.radius), mixArgb(border, text2, hv * 0.6f));
		}
		if (label != null) {
			text(label, x + size + 6, y + (size - capH()) / 2 + 1, text);
		}
		return clicked(id);
	}

	public int segmented(String id, int x, int y, String[] labels, int selectedIndex) {
		int h = buttonH();
		int w = 0;
		int[] widths = new int[labels.length];
		for (int i = 0; i < labels.length; i++) {
			widths[i] = textW(labels[i]) + 14;
			w += widths[i];
		}
		panel(x, y, w, h, surface2, border, Math.min(th.radius, h / 2));
		int cx = x;
		int result = -1;
		for (int i = 0; i < labels.length; i++) {
			String bid = id + "#" + i;
			hit(bid, cx, y, widths[i], h);
			boolean sel = i == selectedIndex;
			float hv = hoverAnim(bid);
			if (sel) {
				roundRect(cx + 1, y + 1, widths[i] - 2, h - 2, Math.max(0, Math.min(th.radius, h / 2) - 1), argb(th.accent, th.dark ? 0.22f : 0.16f));
			} else if (hv > 0) {
				fill(cx + 1, y + 1, widths[i] - 2, h - 2, withAlpha(hover, hv));
			}
			textMid(labels[i], cx + 7, y, h, sel ? text : text2);
			if (clicked(bid)) {
				result = i;
			}
			cx += widths[i];
		}
		return result;
	}

	public int segmentedWidth(String[] labels) {
		int w = 0;
		for (String l : labels) {
			w += textW(l) + 14;
		}
		return w;
	}

	public int tabs(String id, int x, int y, int maxW, String[] labels, int selectedIndex) {
		int h = rowH();
		int total = 0;
		for (String l : labels) {
			total += textW(l) + 16;
		}
		int pad = 16;
		if (total > maxW) {
			pad = 10;
			total = 0;
			for (String l : labels) {
				total += textW(l) + pad;
			}
		}
		if (total > maxW) {
			int sel = dropdown(id + "#dd", x, y + 2, Math.min(maxW, 160), h - 4, java.util.List.of(labels), selectedIndex);
			hline(x, y + h, maxW, border);
			return sel != selectedIndex ? sel : -1;
		}
		int cx = x;
		int result = -1;
		for (int i = 0; i < labels.length; i++) {
			int w = textW(labels[i]) + pad;
			String tid = id + "#" + i;
			hit(tid, cx, y, w, h);
			float hv = hoverAnim(tid);
			boolean sel = i == selectedIndex;
			textMid(labels[i], cx + pad / 2, y, h - 2, sel ? text : mixArgb(text2, text, hv));
			if (sel) {
				float ax = anim(id + "#x", cx);
				float aw = anim(id + "#w", w);
				fill(Math.round(ax) + pad / 2 - 2, y + h - 2, Math.round(aw) - pad + 4, 2, accent);
			}
			if (clicked(tid)) {
				result = i;
			}
			cx += w;
		}
		hline(x, y + h, maxW, border);
		return result;
	}

	public float slider(String id, int x, int y, int w, float value, float min, float max, float step) {
		int h = 12;
		hit(id, x, y, w, h);
		if (clicked(id)) {
			dragId = id;
		}
		if (id.equals(dragId) && mouseDown) {
			float t = Math.max(0, Math.min(1, (mx - x) / (float) w));
			value = min + t * (max - min);
			if (step > 0) {
				value = Math.round(value / step) * step;
			}
			value = Math.max(min, Math.min(max, value));
		}
		float t = (value - min) / (max - min);
		int trackY = y + h / 2 - 1;
		roundRect(x, trackY, w, 3, 1, argb(th.dark ? mix(th.surface2, 0xFFFFFF, 0.1f) : mix(th.surface2, 0x000000, 0.12f), 1f));
		roundRect(x, trackY, Math.round(w * t), 3, 1, accent);
		float hv = hoverAnim(id);
		int k = 9 + Math.round(hv);
		int kx = Math.round(x + w * t - k / 2f);
		roundRect(kx, y + (h - k) / 2, k, k, k / 2, argb(th.dark ? 0xEEF0F4 : 0xFFFFFF, 1f));
		roundBorder(kx, y + (h - k) / 2, k, k, k / 2, withAlpha(accent, 0.6f + 0.4f * hv));
		return value;
	}

	public boolean isDragging(String id) {
		return id.equals(dragId);
	}

	public int dropdown(String id, int x, int y, int w, int h, List<String> options, int selectedIndex) {
		Integer pending = popupResult.remove(id);
		if (pending != null) {
			selectedIndex = pending;
		}
		hit(id, x, y, w, h);
		boolean open = id.equals(openPopup);
		float hv = hoverAnim(id);
		panel(x, y, w, h, mixArgb(surface2, argb(th.dark ? 0xFFFFFF : 0x000000, 1f), 0.04f * hv), open ? withAlpha(accent, 0.8f) : border,
			Math.min(th.radius, h / 2));
		String label = selectedIndex >= 0 && selectedIndex < options.size() ? options.get(selectedIndex) : "";
		textMid(ellipsize(label, w - 22), x + 7, y, h, text);
		icon(Icons.CHEVRON_DOWN, x + w - 14, y + (h - 9) / 2 + 1, text2);
		if (clicked(id)) {
			openPopup = open ? null : id;
			open = !open;
		}
		if (open) {
			final int sel = selectedIndex;
			defer(() -> drawDropdownPopup(id, x, y, w, h, options, sel));
		}
		return selectedIndex;
	}

	private void drawDropdownPopup(String id, int x, int y, int w, int h, List<String> options, int sel) {
		int itemH = rowH() - 2;
		int maxVisible = Math.max(3, Math.min(options.size(), (height - 20) / itemH / 2));
		int listH = Math.min(options.size(), maxVisible) * itemH + 4;
		int py = y + h + 2;
		if (py + listH > height - 4) {
			py = y - listH - 2;
		}
		int popupW = w;
		for (String o : options) {
			popupW = Math.max(popupW, textW(o) + 20);
		}
		popupW = Math.min(popupW, width - 8);
		int px = Math.min(x, width - popupW - 4);
		float open = anim(id + "#open", 1f, 22f);
		int shownH = Math.round(listH * (0.85f + 0.15f * open));
		hit(id + "#popup", px, py, popupW, shownH);
		panel(px, py, popupW, shownH, argb(th.surface2, 1f), border, Math.min(th.radius, 4));
		String sid = id + "#list";
		int offset = beginScroll(sid, px + 2, py + 2, popupW - 4, shownH - 4);
		for (int i = 0; i < options.size(); i++) {
			int iy = py + 2 + i * itemH - offset;
			if (!visible(iy, itemH)) {
				continue;
			}
			String oid = id + "#opt" + i;
			hit(oid, px + 2, iy, popupW - 4, itemH);
			float hv = hoverAnim(oid);
			if (i == sel) {
				fill(px + 2, iy, popupW - 4, itemH, selected);
			}
			if (hv > 0) {
				fill(px + 2, iy, popupW - 4, itemH, withAlpha(hover, hv * 1.6f));
			}
			textMid(ellipsize(options.get(i), popupW - 16), px + 8, iy, itemH, i == sel ? text : text2);
			if (clicked(oid)) {
				popupResult.put(id, i);
				openPopup = null;
			}
		}
		endScroll(sid, options.size() * itemH);
	}

	public boolean popupOpen() {
		return openPopup != null;
	}

	public void closePopup() {
		openPopup = null;
	}

	public static final class Scroll {
		float offset;
		float target;
		int contentH;
		int viewH;
		int x;
		int y;
		int w;
		boolean stickBottom;
	}

	public int beginScroll(String id, int x, int y, int w, int h) {
		Scroll s = scrolls.computeIfAbsent(id, k -> new Scroll());
		s.viewH = h;
		s.x = x;
		s.y = y;
		s.w = w;
		float max = Math.max(0, s.contentH - h);
		s.target = Math.max(0, Math.min(max, s.target));
		if (animIntensity <= 0.01f) {
			s.offset = s.target;
		} else {
			s.offset += (s.target - s.offset) * (1f - (float) Math.exp(-20f * dt));
			if (Math.abs(s.offset - s.target) < 0.5f) {
				s.offset = s.target;
			}
		}
		scrollRegions.add(new Hit(id, x, y, w, h, layer, seq++));
		pushClip(x, y, w, h);
		return Math.round(s.offset);
	}

	public void endScroll(String id, int contentH) {
		popClip();
		Scroll s = scrolls.get(id);
		s.contentH = contentH;
		if (contentH <= s.viewH) {
			return;
		}
		int barW = 3;
		int bx = s.x + s.w - barW - 1;
		int thumbH = Math.max(18, Math.round(s.viewH * (s.viewH / (float) contentH)));
		float max = contentH - s.viewH;
		int thumbY = s.y + Math.round((s.viewH - thumbH) * (s.offset / max));
		String tid = id + "#thumb";
		hit(tid, bx - 3, s.y, barW + 4, s.viewH);
		if (clicked(tid)) {
			dragId = tid;
			dragAnchor = my;
			dragAnchorValue = my >= thumbY && my < thumbY + thumbH ? s.target : (my - s.y - thumbH / 2f) / (s.viewH - thumbH) * max;
			s.target = dragAnchorValue;
		}
		if (tid.equals(dragId) && mouseDown) {
			float delta = (my - dragAnchor) / (float) (s.viewH - thumbH) * max;
			s.target = Math.max(0, Math.min(max, dragAnchorValue + delta));
			s.offset = s.target;
		}
		float hv = hoverAnim(tid);
		roundRect(bx, thumbY, barW, thumbH, 1, withAlpha(text2, 0.28f + 0.3f * hv));
	}

	public void scrollTo(String id, int target) {
		Scroll s = scrolls.computeIfAbsent(id, k -> new Scroll());
		s.target = Math.max(0, target);
	}

	/** Shows the end of a list immediately, without animating. Call after endScroll so the content height is known. */
	public void jumpToEnd(String id) {
		Scroll s = scrolls.computeIfAbsent(id, k -> new Scroll());
		s.target = s.offset = Math.max(0, s.contentH - s.viewH);
	}

	public void scrollBy(String id, int delta) {
		Scroll s = scrolls.computeIfAbsent(id, k -> new Scroll());
		s.target = Math.max(0, s.target + delta);
	}

	public int scrollOffset(String id) {
		Scroll s = scrolls.get(id);
		return s == null ? 0 : Math.round(s.offset);
	}

	public int scrollView(String id) {
		Scroll s = scrolls.get(id);
		return s == null ? 0 : s.viewH;
	}

	public boolean nearBottom(String id, int slack) {
		Scroll s = scrolls.get(id);
		return s != null && s.contentH > 0 && s.offset + s.viewH >= s.contentH - slack;
	}

	public TextField field(String id) {
		return fields.computeIfAbsent(id, k -> new TextField());
	}

	public TextField textField(String id, int x, int y, int w, int h, String placeholder, Icons icon) {
		TextField f = field(id);
		fieldOrder.add(id);
		hit(id, x, y, w, h);
		boolean focused = id.equals(focusId);
		int padL = icon != null ? 18 : 7;
		if (clicked(id)) {
			focusId = id;
			focused = true;
			f.placeCursor(this, mx - (x + padL), isShiftDown());
			if (wasDoubleClick()) {
				f.selectAll();
			}
		} else if (focused && mouseDown && id.equals(pressedId)) {
			f.dragTo(this, mx - (x + padL));
		}
		float hv = hoverAnim(id);
		float fa = anim(id + "#f", focused ? 1f : 0f, 20f);
		int bc = mixArgb(mixArgb(border, text3, hv * 0.4f), accent, fa);
		panel(x, y, w, h, argb(th.dark ? mix(th.bg, th.surface, 0.5f) : 0xFFFFFF, 1f), bc, Math.min(th.radius, h / 2));
		if (icon != null) {
			icon(icon, x + 6, y + (h - 9) / 2, focused ? accent : text3);
		}
		int innerW = w - padL - 6;
		pushClip(x + padL, y + 1, innerW, h - 2);
		int ty = y + (h - capH()) / 2 + 1;
		if (f.text.isEmpty() && !focused) {
			text(placeholder, x + padL, ty, text3);
		} else {
			f.ensureVisible(this, innerW);
			int baseX = x + padL - f.scrollPx;
			if (focused && f.hasSelection()) {
				int a = textW(f.text.substring(0, f.selMin()));
				int b = textW(f.text.substring(0, f.selMax()));
				fill(baseX + a, ty - 2, b - a, capH() + 3, argb(th.accent, 0.35f));
			}
			text(f.text, baseX, ty, text);
			if (focused && (frameMs / 530) % 2 == 0) {
				int cx = baseX + textW(f.text.substring(0, f.cursor));
				fill(cx, ty - 2, 1, capH() + 3, text);
			}
		}
		popClip();
		if (!f.text.isEmpty()) {
			String cid = id + "#clear";
			int cs = h - 6;
			hit(cid, x + w - cs - 3, y + 3, cs, cs);
			if (hovered(cid)) {
				icon(Icons.CLOSE, x + w - cs - 3 + (cs - 9) / 2 + 1, y + (h - 9) / 2 + 1, text2);
			}
			if (clicked(cid)) {
				f.set("");
				f.changed = true;
				focusId = id;
			}
		}
		return f;
	}

	public boolean isFocused(String id) {
		return id.equals(focusId);
	}

	public void focus(String id) {
		focusId = id;
	}

	public void blur() {
		focusId = null;
	}

	public String focused() {
		return focusId;
	}

	public boolean hasFocusedField() {
		return focusId != null && fields.containsKey(focusId);
	}

	public TextField focusedField() {
		return focusId == null ? null : fields.get(focusId);
	}

	public void cycleFocus(boolean backwards) {
		if (fieldOrder.isEmpty()) {
			return;
		}
		int idx = focusId == null ? -1 : fieldOrder.indexOf(focusId);
		int next = backwards ? (idx <= 0 ? fieldOrder.size() - 1 : idx - 1) : (idx + 1) % fieldOrder.size();
		focusId = fieldOrder.get(next);
		fields.get(focusId).selectAll();
	}

	public boolean isShiftDown() {
		long h = mc.getWindow().getHandle();
		return org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == 1
			|| org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == 1;
	}

	public void badge(int x, int y, String label, int colorArgb) {
		int w = textW(label) + 8;
		int h = lineH() + 3;
		roundRect(x, y, w, h, Math.min(th.radius, 3), withAlpha(colorArgb, th.dark ? 0.16f : 0.13f));
		text(label, x + 4, y + 2, colorArgb);
	}

	public int badgeW(String label) {
		return textW(label) + 8;
	}

	public boolean emptyState(String id, int x, int y, int w, int h, Icons icon, String title, String message, String action) {
		int contentH = 9 + 10 + lineH() + 6 + paragraphHeight(message, Math.min(w - 40, 280)) + (action != null ? buttonH() + 12 : 0);
		int cy = y + Math.max(8, (h - contentH) / 2);
		int cx = x + w / 2;
		roundRect(cx - 12, cy - 7, 24, 24, 12, argb(th.accent, 0.12f));
		icon(icon, cx - 4, cy, accent);
		cy += 26;
		textCenter(title, cx, cy, text);
		cy += lineH() + 6;
		int pw = Math.min(w - 40, 280);
		List<OrderedText> lines = wrap(message, pw);
		for (OrderedText l : lines) {
			text(l, cx - textW(l) / 2, cy, text2);
			cy += lineH() + 2;
		}
		if (action != null) {
			cy += 8;
			int bw = buttonWidth(action, null);
			return button(id, cx - bw / 2, cy, bw, buttonH(), action, Btn.SECONDARY);
		}
		return false;
	}

	public void spinner(int cx, int cy, int argb) {
		long t = frameMs / 90;
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * i / 8;
			int px = cx + (int) Math.round(Math.cos(a) * 6);
			int py = cy + (int) Math.round(Math.sin(a) * 6);
			float fade = ((i - t) % 8 + 8) % 8 / 8f;
			fill(px - 1, py - 1, 2, 2, withAlpha(argb, 0.2f + 0.8f * fade));
		}
	}

	public void loading(int x, int y, int w, int h, String label) {
		spinner(x + w / 2, y + h / 2 - 8, accent);
		textCenter(label, x + w / 2, y + h / 2 + 6, text2);
	}

	public void stat(int x, int y, int w, String label, String value, String sub, int valueColor) {
		text(ellipsize(label.toUpperCase(java.util.Locale.ROOT), w), x, y, text3);
		float old = fontScale;
		fontScale = old * 1.5f;
		text(ellipsize(value, w), x, y + lineH() / 2 + 8, valueColor);
		int vh = lineH();
		fontScale = old;
		if (sub != null) {
			text(ellipsize(sub, w), x, y + 10 + vh + 4, text2);
		}
	}

	public int statH(boolean withSub) {
		return 10 + Math.round(9 * fontScale * 1.5f) + 4 + (withSub ? lineH() + 2 : 0);
	}

	public void copyToClipboard(String s) {
		mc.keyboard.setClipboard(s);
	}
}
