package com.sessioniq.gui;

import com.sessioniq.analytics.DateRange;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class DateRangeControl {
	private final String id;
	private DateRange range;
	private final List<DateRange.Preset> presets;
	private boolean changed;

	public DateRangeControl(String id, DateRange.Preset initial, List<DateRange.Preset> presets) {
		this.id = id;
		this.presets = presets;
		this.range = DateRange.of(initial, Fmt.zone());
	}

	public DateRange range() {
		if (range.preset() != DateRange.Preset.CUSTOM && range.preset() != DateRange.Preset.ALL) {
			DateRange fresh = DateRange.of(range.preset(), Fmt.zone());
			if (!fresh.equals(range)) {
				range = fresh;
			}
		}
		return range;
	}

	public void set(DateRange r) {
		range = r;
		changed = true;
	}

	public boolean consumeChanged() {
		boolean c = changed;
		changed = false;
		return c;
	}

	public int width(Ui ui) {
		return Math.max(96, ui.textW(label()) + 26);
	}

	private String label() {
		return range.preset() == DateRange.Preset.CUSTOM ? range.describe() : range.preset().label;
	}

	public void render(Ui ui, SiqScreen screen, int x, int y, int w, int h) {
		List<String> options = new ArrayList<>();
		int selected = -1;
		for (int i = 0; i < presets.size(); i++) {
			DateRange.Preset p = presets.get(i);
			options.add(p == DateRange.Preset.CUSTOM ? (range.preset() == DateRange.Preset.CUSTOM ? range.describe() : "Custom range…") : p.label);
			if (p == range.preset()) {
				selected = i;
			}
		}
		int result = ui.dropdown(id, x, y, w, h, options, selected);
		if (result != selected && result >= 0) {
			DateRange.Preset p = presets.get(result);
			if (p == DateRange.Preset.CUSTOM) {
				openCustom(ui, screen);
			} else {
				set(DateRange.of(p, Fmt.zone()));
			}
		}
	}

	public void openCustom(Ui ui, SiqScreen screen) {
		screen.openModal(new CustomRangeModal(ui));
	}

	private final class CustomRangeModal extends Modal {
		private final String fromId = id + "#from";
		private final String toId = id + "#to";
		private String error;

		CustomRangeModal(Ui ui) {
			LocalDate today = LocalDate.now(Fmt.zone());
			ui.field(fromId).set(range.from() != null ? range.from().toString() : today.minusDays(29).toString());
			ui.field(toId).set(range.to() != null ? range.to().toString() : today.toString());
			ui.field(fromId).maxLength = 10;
			ui.field(toId).maxLength = 10;
		}

		@Override
		public String title() {
			return "Custom date range";
		}

		@Override
		public int preferredWidth(Ui ui) {
			return 280;
		}

		@Override
		public int bodyHeight(Ui ui, int w) {
			return 2 * (ui.lineH() + 30) + ui.lineH() + 14 + (error != null ? ui.lineH() + 6 : 0);
		}

		@Override
		public void renderBody(Ui ui, int x, int y, int w) {
			int cy = y + 6;
			ui.text("Dates are inclusive and use your display time zone (" + Fmt.zone().getId() + ").", x, cy, ui.text3);
			cy += ui.lineH() + 8;
			ui.text("From (YYYY-MM-DD)", x, cy, ui.text2);
			ui.textField(fromId, x, cy + ui.lineH() + 4, w, 20, "2026-01-01", Icons.CALENDAR);
			cy += ui.lineH() + 30;
			ui.text("To (YYYY-MM-DD)", x, cy, ui.text2);
			ui.textField(toId, x, cy + ui.lineH() + 4, w, 20, "2026-12-31", Icons.CALENDAR);
			cy += ui.lineH() + 30;
			if (error != null) {
				ui.text(error, x, cy, ui.error);
			}
			if (ui.field(fromId).consumeSubmitted() || ui.field(toId).consumeSubmitted()) {
				apply(ui);
			}
		}

		private void apply(Ui ui) {
			try {
				LocalDate from = LocalDate.parse(ui.field(fromId).text.trim());
				LocalDate to = LocalDate.parse(ui.field(toId).text.trim());
				set(DateRange.custom(from, to));
				close();
			} catch (DateTimeParseException e) {
				error = "Use the format YYYY-MM-DD, e.g. 2026-10-15";
			}
		}

		@Override
		public void renderButtons(Ui ui, int x, int y, int w, int h) {
			if (ui.button(id + "#apply", x + w - 60, y, 60, h, "Apply", Ui.Btn.PRIMARY)) {
				apply(ui);
			}
			if (ui.button(id + "#cancel", x + w - 126, y, 60, h, "Cancel", Ui.Btn.SECONDARY)) {
				close();
			}
		}
	}
}
