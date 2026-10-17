package com.sessioniq.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SiqLog {
	public static final Logger LOGGER = LoggerFactory.getLogger("SessionIQ");
	private static volatile boolean diagnostics;

	private SiqLog() {
	}

	public static void setDiagnostics(boolean enabled) {
		diagnostics = enabled;
	}

	public static boolean diagnostics() {
		return diagnostics;
	}

	public static void debug(String message, Object... args) {
		if (diagnostics) {
			LOGGER.info("[diag] " + message, args);
		}
	}

	public static void info(String message, Object... args) {
		LOGGER.info(message, args);
	}

	public static void warn(String message, Object... args) {
		LOGGER.warn(message, args);
	}

	public static void error(String message, Throwable t) {
		LOGGER.error(message, t);
	}
}
