package com.sessioniq.gui;

import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

public final class Fmt {
	private Fmt() {
	}

	private static SiqConfig config() {
		return SessionIQ.get().config.get();
	}

	public static ZoneId zone() {
		String z = config().timeZone;
		try {
			return "system".equals(z) ? ZoneId.systemDefault() : ZoneId.of(z);
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}

	public static String duration(long ms) {
		if (ms < 0) {
			ms = 0;
		}
		long s = ms / 1000;
		long d = s / 86400;
		long h = s % 86400 / 3600;
		long m = s % 3600 / 60;
		long sec = s % 60;
		if (d > 0) {
			return d + "d " + h + "h";
		}
		if (h > 0) {
			return h + "h " + pad(m) + "m";
		}
		if (m > 0) {
			return m + "m " + pad(sec) + "s";
		}
		return sec + "s";
	}

	public static String durationShort(long ms) {
		double h = ms / 3_600_000.0;
		if (h >= 10) {
			return Math.round(h) + "h";
		}
		if (h >= 1) {
			return String.format(Locale.ROOT, "%.1fh", h);
		}
		long m = Math.round(ms / 60000.0);
		return m + "m";
	}

	private static String pad(long v) {
		return v < 10 ? "0" + v : String.valueOf(v);
	}

	private static String datePattern() {
		return switch (config().dateFormat) {
			case "DMY" -> "dd/MM/yyyy";
			case "MDY" -> "MM/dd/yyyy";
			default -> "yyyy-MM-dd";
		};
	}

	private static String timePattern(boolean seconds) {
		return config().clock24h ? (seconds ? "HH:mm:ss" : "HH:mm") : (seconds ? "h:mm:ss a" : "h:mm a");
	}

	private static String suffix(ZonedDateTime z) {
		return config().showTimeZone ? " " + z.getZone().getDisplayName(TextStyle.SHORT, Locale.ROOT) : "";
	}

	public static String date(long millis) {
		return DateTimeFormatter.ofPattern(datePattern(), Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(zone()));
	}

	public static String date(LocalDate d) {
		return DateTimeFormatter.ofPattern(datePattern(), Locale.ROOT).format(d);
	}

	public static String time(long millis) {
		ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(zone());
		return DateTimeFormatter.ofPattern(timePattern(false), Locale.ENGLISH).format(z) + suffix(z);
	}

	public static String timeSeconds(long millis) {
		ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(zone());
		return DateTimeFormatter.ofPattern(timePattern(true), Locale.ENGLISH).format(z) + suffix(z);
	}

	public static String dateTime(long millis) {
		ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(zone());
		return DateTimeFormatter.ofPattern(datePattern() + " " + timePattern(false), Locale.ENGLISH).format(z) + suffix(z);
	}

	public static String dateTimeSeconds(long millis) {
		ZonedDateTime z = Instant.ofEpochMilli(millis).atZone(zone());
		return DateTimeFormatter.ofPattern(datePattern() + " " + timePattern(true), Locale.ENGLISH).format(z) + suffix(z);
	}

	public static String dayLabel(LocalDate d) {
		LocalDate today = LocalDate.now(zone());
		if (d.equals(today)) {
			return "Today";
		}
		if (d.equals(today.minusDays(1))) {
			return "Yesterday";
		}
		if (d.isAfter(today.minusDays(7)) && !d.isAfter(today)) {
			return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + date(d);
		}
		return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ", " + date(d);
	}

	public static LocalDate localDate(long millis) {
		return Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate();
	}

	public static String relative(long millis) {
		long diff = System.currentTimeMillis() - millis;
		if (diff < 0) {
			return dateTime(millis);
		}
		long min = diff / 60000;
		if (min < 1) {
			return "just now";
		}
		if (min < 60) {
			return min + " min ago";
		}
		long h = min / 60;
		if (h < 24) {
			return h + (h == 1 ? " hour ago" : " hours ago");
		}
		long d = h / 24;
		if (d < 30) {
			return d + (d == 1 ? " day ago" : " days ago");
		}
		return date(millis);
	}

	public static String count(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	public static String bytes(long b) {
		if (b < 1024) {
			return b + " B";
		}
		if (b < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
		}
		if (b < 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
		}
		return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
	}

	public static String ms(double v) {
		return Double.isFinite(v) ? Math.round(v) + " ms" : "—";
	}

	public static String mb(double v) {
		return Double.isFinite(v) ? Math.round(v) + " MB" : "—";
	}

	public static String plural(long n, String one, String many) {
		return count(n) + " " + (n == 1 ? one : many);
	}

	public static String sessionType(String type) {
		return switch (type == null ? "" : type) {
			case "multiplayer" -> "Multiplayer";
			case "singleplayer" -> "Single-player";
			case "mixed" -> "Mixed";
			default -> "Menus only";
		};
	}
}
