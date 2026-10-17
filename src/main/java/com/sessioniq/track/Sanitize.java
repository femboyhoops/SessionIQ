package com.sessioniq.track;

public final class Sanitize {
	private Sanitize() {
	}

	public static String text(String s, int maxLength) {
		if (s == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(Math.min(s.length(), maxLength));
		for (int i = 0; i < s.length() && sb.length() < maxLength; i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				i++;
				continue;
			}
			if (c == '\n' || c == '\t') {
				sb.append(c == '\t' ? ' ' : '\n');
			} else if (!Character.isISOControl(c) && c != '​' && c != ' ' && c != ' ') {
				sb.append(c);
			}
		}
		if (s.length() > maxLength && sb.length() >= maxLength) {
			sb.append('…');
		}
		return sb.toString().strip();
	}

	public static String clean(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' && i + 1 < s.length()) {
				i++;
			} else if (c == '\n') {
				sb.append('\n');
			} else if (c == '\t') {
				sb.append(' ');
			} else if (!Character.isISOControl(c) && c != '​' && c != ' ' && c != ' ') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	public static String oneLine(String s, int maxLength) {
		String t = text(s, maxLength);
		return t == null ? null : t.replace('\n', ' ');
	}
}
