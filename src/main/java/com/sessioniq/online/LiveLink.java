package com.sessioniq.online;

import com.sessioniq.SessionIQ;
import com.sessioniq.util.SiqLog;

import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * The live connection to the online service. The server only sends small "something changed"
 * events through it (a friend request, a message, a friend's status); the data itself is then
 * reloaded through the normal API. Reconnects with backoff and sends a keep-alive every 40 s.
 * Everything except the network callbacks runs on the client thread.
 */
final class LiveLink {
	private static final long PING_MS = 40_000;
	private static final long[] BACKOFF_MS = {3_000, 10_000, 30_000, 120_000};

	private final Consumer<String> onEvent;
	private final Runnable onConnected;
	private final Runnable onUnauthorized;
	private WebSocket socket;
	private boolean connecting;
	private long nextAttemptAt;
	private int failures;
	private long lastPingAt;
	private int generation;

	LiveLink(Consumer<String> onEvent, Runnable onConnected, Runnable onUnauthorized) {
		this.onEvent = onEvent;
		this.onConnected = onConnected;
		this.onUnauthorized = onUnauthorized;
	}

	boolean connected() {
		return socket != null;
	}

	void tick(ApiClient api, String token, long now) {
		if (socket != null) {
			if (now - lastPingAt > PING_MS) {
				lastPingAt = now;
				socket.sendText("ping", true);
			}
			return;
		}
		if (connecting || now < nextAttemptAt) {
			return;
		}
		connecting = true;
		int gen = ++generation;
		api.webSocket("/v1/live", token, new Listener(gen)).whenCompleteAsync((ws, error) -> {
			connecting = false;
			if (gen != generation) {
				if (ws != null) {
					ws.abort();
				}
				return;
			}
			if (error != null) {
				Throwable cause = error;
				while (cause.getCause() != null && !(cause instanceof java.net.http.WebSocketHandshakeException)) {
					cause = cause.getCause();
				}
				if (cause instanceof java.net.http.WebSocketHandshakeException h && h.getResponse().statusCode() == 401) {
					onUnauthorized.run();
				}
				retryLater();
				SiqLog.debug("Live connection failed: {}", ApiClient.message(error));
				return;
			}
			socket = ws;
			failures = 0;
			lastPingAt = System.currentTimeMillis();
			onConnected.run();
		}, SessionIQ.mainThread());
	}

	private void retryLater() {
		nextAttemptAt = System.currentTimeMillis() + BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
		failures++;
	}

	/** Closes the connection and stops reconnecting until the next tick that wants it. */
	void close() {
		generation++;
		connecting = false;
		failures = 0;
		nextAttemptAt = 0;
		if (socket != null) {
			WebSocket s = socket;
			socket = null;
			try {
				s.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(1, java.util.concurrent.TimeUnit.SECONDS).exceptionally(e -> {
					s.abort();
					return null;
				});
			} catch (Exception e) {
				s.abort();
			}
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder partial = new StringBuilder();

		Listener(int gen) {
			this.gen = gen;
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String message = partial.toString();
				partial.setLength(0);
				if (!"pong".equals(message)) {
					SessionIQ.mainThread().execute(() -> {
						if (gen == generation) {
							onEvent.accept(message);
						}
					});
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			dropped();
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			dropped();
		}

		private void dropped() {
			SessionIQ.mainThread().execute(() -> {
				if (gen == generation && socket != null) {
					socket = null;
					retryLater();
				}
			});
		}
	}
}
