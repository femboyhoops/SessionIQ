package com.sessioniq.analytics;

import java.time.LocalDate;
import java.time.ZoneId;

public record DateRange(Preset preset, LocalDate from, LocalDate to) {
	public enum Preset {
		TODAY("Today"), LAST_7("Last 7 days"), LAST_30("Last 30 days"), LAST_90("Last 90 days"), ALL("All time"), CUSTOM("Custom");

		public final String label;

		Preset(String label) {
			this.label = label;
		}
	}

	public static DateRange of(Preset preset, ZoneId zone) {
		LocalDate today = LocalDate.now(zone);
		return switch (preset) {
			case TODAY -> new DateRange(preset, today, today);
			case LAST_7 -> new DateRange(preset, today.minusDays(6), today);
			case LAST_30 -> new DateRange(preset, today.minusDays(29), today);
			case LAST_90 -> new DateRange(preset, today.minusDays(89), today);
			case ALL, CUSTOM -> new DateRange(Preset.ALL, null, null);
		};
	}

	public static DateRange custom(LocalDate from, LocalDate to) {
		if (from != null && to != null && to.isBefore(from)) {
			LocalDate t = from;
			from = to;
			to = t;
		}
		return new DateRange(Preset.CUSTOM, from, to);
	}

	public boolean isAll() {
		return from == null && to == null;
	}

	public long startMillis(ZoneId zone) {
		return from == null ? Long.MIN_VALUE : from.atStartOfDay(zone).toInstant().toEpochMilli();
	}

	public long endMillis(ZoneId zone) {
		return to == null ? Long.MAX_VALUE : to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
	}

	public boolean contains(long millis, ZoneId zone) {
		return millis >= startMillis(zone) && millis < endMillis(zone);
	}

	public boolean overlaps(long start, long end, ZoneId zone) {
		return end > startMillis(zone) && start < endMillis(zone);
	}

	public long clip(long start, long end, ZoneId zone) {
		long s = Math.max(start, startMillis(zone));
		long e = Math.min(end, endMillis(zone));
		return Math.max(0, e - s);
	}

	public String describe() {
		if (preset != Preset.CUSTOM) {
			return preset.label;
		}
		return (from == null ? "…" : from.toString()) + " – " + (to == null ? "…" : to.toString());
	}
}
