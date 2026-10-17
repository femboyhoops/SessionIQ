package com.sessioniq.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class Ids {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final DateTimeFormatter SESSION_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private Ids() {
	}

	public static String session(long startedAtMillis) {
		return SESSION_STAMP.format(Instant.ofEpochMilli(startedAtMillis)) + "-" + hex(4);
	}

	public static String visit() {
		return "v-" + hex(12);
	}

	public static String disconnect() {
		return "d-" + hex(12);
	}

	public static String marker() {
		return "m-" + hex(12);
	}

	public static String chat() {
		return hex(16);
	}

	public static String hex(int length) {
		char[] out = new char[length];
		for (int i = 0; i < length; i++) {
			out[i] = HEX[RANDOM.nextInt(16)];
		}
		return new String(out);
	}

	public static boolean isSafeFileComponent(String id) {
		if (id == null || id.isEmpty() || id.length() > 64) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_')) {
				return false;
			}
		}
		return true;
	}
}
