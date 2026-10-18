package com.sessioniq.online;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sessioniq.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * HTTPS client for the SessionIQ online service. It only ever talks to the one base URL built
 * into the mod and sends JSON bodies built by this mod; nothing else is read or uploaded.
 */
public final class ApiClient {
	private static final Duration TIMEOUT = Duration.ofSeconds(12);

	private final String base;
	private final String userAgent;
	private final HttpClient http;

	public ApiClient(String base, String modVersion, Executor executor) {
		this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
		this.userAgent = "SessionIQ/" + modVersion;
		this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
	}

	/** True when the URL is HTTPS, or plain HTTP to this computer (for testing a local backend). */
	public static boolean acceptable(String url) {
		try {
			URI u = URI.create(url.trim());
			String host = u.getHost();
			if (host == null) {
				return false;
			}
			return "https".equals(u.getScheme()) || "http".equals(u.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1"));
		} catch (Exception e) {
			return false;
		}
	}

	public static final class ApiException extends RuntimeException {
		public final int status;

		public ApiException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	/** Opens the live connection (wss:// for https services) used for instant updates. */
	public CompletableFuture<java.net.http.WebSocket> webSocket(String path, String token, java.net.http.WebSocket.Listener listener) {
		String ws = base.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
		return http.newWebSocketBuilder()
			.connectTimeout(TIMEOUT)
			.header("authorization", "Bearer " + token)
			.header("user-agent", userAgent)
			.buildAsync(URI.create(ws + path), listener);
	}

	public <T> CompletableFuture<T> get(String path, String token, Class<T> type) {
		return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), token, type);
	}

	public <T> CompletableFuture<T> post(String path, Object body, String token, Class<T> type) {
		String json = body == null ? "{}" : body instanceof JsonElement e ? e.toString() : Json.COMPACT.toJson(body);
		return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
			.header("content-type", "application/json"), token, type);
	}

	private <T> CompletableFuture<T> send(HttpRequest.Builder builder, String token, Class<T> type) {
		builder.timeout(TIMEOUT).header("user-agent", userAgent).header("accept", "application/json");
		if (token != null) {
			builder.header("authorization", "Bearer " + token);
		}
		return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((res, err) -> {
			if (err != null) {
				Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				throw new ApiException(0, "Could not reach the SessionIQ server (" + cause.getClass().getSimpleName() + ")");
			}
			JsonObject obj = null;
			try {
				JsonElement el = JsonParser.parseString(res.body());
				obj = el.isJsonObject() ? el.getAsJsonObject() : null;
			} catch (Exception ignored) {
			}
			if (res.statusCode() != 200) {
				String message = obj != null && obj.has("error") ? obj.get("error").getAsString() : "The SessionIQ server answered " + res.statusCode();
				throw new ApiException(res.statusCode(), message);
			}
			if (obj == null) {
				throw new ApiException(res.statusCode(), "The SessionIQ server sent an unreadable answer");
			}
			return Json.COMPACT.fromJson(obj, type);
		});
	}

	public static String message(Throwable t) {
		Throwable c = t;
		while (c instanceof CompletionException && c.getCause() != null) {
			c = c.getCause();
		}
		return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
	}

	public static int status(Throwable t) {
		Throwable c = t;
		while (c instanceof CompletionException && c.getCause() != null) {
			c = c.getCause();
		}
		return c instanceof ApiException a ? a.status : -1;
	}
}
