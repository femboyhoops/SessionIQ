package com.sessioniq.track;

import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class CommandEchoGuard {
	private static final long WINDOW_MS = 10_000;
	private static final int MIN_FRAGMENT = 4;

	private final Deque<Entry> recent = new ArrayDeque<>();

	private record Entry(long at, List<String> fragments) {
	}

	public void onCommandSent(String command) {
		if (command == null) {
			return;
		}
		String trimmed = command.trim();
		List<String> fragments = new ArrayList<>();
		add(fragments, trimmed);
		String[] tokens = trimmed.split("\\s+");
		for (int k = 1; k < tokens.length && k <= 3; k++) {
			add(fragments, String.join(" ", java.util.Arrays.copyOfRange(tokens, k, tokens.length)));
		}
		long now = System.currentTimeMillis();
		prune(now);
		if (!fragments.isEmpty()) {
			recent.addLast(new Entry(now, fragments));
		}
		while (recent.size() > 20) {
			recent.removeFirst();
		}
	}

	private static void add(List<String> out, String fragment) {
		if (fragment.length() >= MIN_FRAGMENT) {
			out.add(fragment.toLowerCase(Locale.ROOT));
		}
	}

	private void prune(long now) {
		while (!recent.isEmpty() && now - recent.peekFirst().at() > WINDOW_MS) {
			recent.removeFirst();
		}
	}

	public boolean isEcho(String text) {
		if (recent.isEmpty() || text == null) {
			return false;
		}
		prune(System.currentTimeMillis());
		String lower = text.toLowerCase(Locale.ROOT);
		for (Entry e : recent) {
			for (String f : e.fragments()) {
				if (lower.contains(f)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isCommandFeedback(Text message) {
		return containsCommandKey(message, 0);
	}

	private static boolean containsCommandKey(Text text, int depth) {
		if (text == null || depth > 8) {
			return false;
		}
		if (text.getContent() instanceof TranslatableTextContent t) {
			String key = t.getKey();
			if (key.startsWith("command.context.") || key.startsWith("command.unknown.") || key.startsWith("command.expected.")) {
				return true;
			}
			for (Object arg : t.getArgs()) {
				if (arg instanceof Text a && containsCommandKey(a, depth + 1)) {
					return true;
				}
			}
		}
		for (Text sibling : text.getSiblings()) {
			if (containsCommandKey(sibling, depth + 1)) {
				return true;
			}
		}
		return false;
	}
}
