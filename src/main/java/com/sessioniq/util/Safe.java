package com.sessioniq.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Safe {
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private Safe() {
	}

	public static void run(String what, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			if (LOGGED.add(what + ":" + t.getClass().getName())) {
				SiqLog.error("SessionIQ: " + what + " failed", t);
			}
			try {
				com.sessioniq.SessionIQ siq = com.sessioniq.SessionIQ.getOrNull();
				if (siq != null) {
					siq.storage.report(what + " failed", t.toString());
				}
			} catch (Throwable ignored) {
			}
		}
	}
}
