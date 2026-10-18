package com.sessioniq.online;

import com.google.gson.JsonObject;
import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.util.SiqLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.network.encryption.PlayerKeyPair;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Everything SessionIQ does online. Nothing is sent until the player has accepted the
 * first-launch notice (NOTICE_VERSION) and online features are on. State is only touched on the
 * client thread; network work runs on a background thread and reports back to the client thread.
 */
public final class OnlineService {
	public static final int NOTICE_VERSION = 1;
	/** Heartbeat with friends (status and messages should feel live) and without (only friend requests can arrive). */
	public static final long PRESENCE_INTERVAL_MS = 120_000;
	public static final long PRESENCE_IDLE_INTERVAL_MS = 600_000;
	public static final long SYNC_INTERVAL_MS = 30 * 60_000;
	private static final long ACTIVITY_CHECK_MS = 5_000;
	private static final long[] SIGN_IN_BACKOFF_MS = {60_000, 5 * 60_000, 30 * 60_000};
	private static final SystemToast.Type TOAST = new SystemToast.Type(6000L);
	private static final SystemToast.Type MESSAGE_TOAST = new SystemToast.Type(5000L);

	public enum Status {
		/** No service URL in this build, or -Dsessioniq.offline=true. */
		UNAVAILABLE,
		/** Turned off in Settings › Online. */
		DISABLED,
		/** Waiting for the player to accept the first-launch notice. */
		NEEDS_NOTICE,
		SIGNING_IN,
		SIGNED_IN,
		/** Sign-in failed; anonymous features (global statistics, suggestions) still work. */
		SIGNED_OUT
	}

	private final SessionIQ siq;
	private final String baseUrl;
	private final ExecutorService worker;
	private final ApiClient api;
	private OnlineState state;

	private boolean signingIn;
	/** onSignedIn ran during this launch (privacy pushed, friends loaded). */
	private boolean sessionStarted;
	private String signInError;
	private long nextSignInAt;
	private int signInFailures;
	private boolean launchSent;
	private long nextPresenceAt;
	private long nextSyncAt;
	private long nextActivityCheckAt;
	private String lastActivity;
	private boolean wasInWorld;
	private String lastSyncFingerprint;
	private String lastPrivacyPushed;
	private boolean syncInFlight;
	private boolean presenceInFlight;
	private boolean noticeShownThisLaunch;
	private Api.Presence presence = new Api.Presence();
	private Api.Friends friends;
	private long friendsAt;
	private boolean friendsLoading;
	private String friendsError;
	private int revision;
	private final LiveLink live = new LiveLink(this::onLiveEvent, this::onLiveConnected, this::onUnauthorized);
	/** When a "friends changed" event arrived; reloads are batched briefly so bursts cost one request. */
	private long friendsReloadAt;
	private final Map<String, Integer> messageSignals = new HashMap<>();
	private String viewingChat;
	private long viewingChatAt;
	private long lastSyncStartAt;
	private int syncGeneration;
	private Integer onlineNow;

	public OnlineService(SessionIQ siq) {
		this.siq = siq;
		String url = System.getProperty("sessioniq.api");
		if (url == null || url.isBlank()) {
			url = FabricLoader.getInstance().getModContainer(SessionIQ.MOD_ID)
				.map(c -> c.getMetadata().getCustomValue("sessioniq:api"))
				.map(v -> v.getAsString())
				.orElse("");
		}
		boolean offline = Boolean.getBoolean("sessioniq.offline");
		this.baseUrl = !offline && !url.isBlank() && !url.contains("${") && ApiClient.acceptable(url) ? url.trim() : null;
		this.worker = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "SessionIQ-Online");
			t.setDaemon(true);
			return t;
		});
		this.api = baseUrl == null ? null : new ApiClient(baseUrl, siq.modVersion, worker);
		if (baseUrl == null && !offline && !url.isBlank()) {
			SiqLog.warn("SessionIQ online features are off: the service URL '{}' is not an HTTPS address", url);
		}
	}

	private SiqConfig cfg() {
		return siq.config.get();
	}

	/** Loads online.json; called from the client thread once the mod has started. */
	public void load() {
		state = OnlineState.load(siq.paths.online);
		wasActive = active();
	}

	private boolean wasActive;

	public Status status() {
		if (api == null) {
			return Status.UNAVAILABLE;
		}
		if (!cfg().onlineEnabled) {
			return Status.DISABLED;
		}
		if (cfg().onlineNoticeAccepted < NOTICE_VERSION) {
			return Status.NEEDS_NOTICE;
		}
		if (signedIn()) {
			return Status.SIGNED_IN;
		}
		return signingIn || signInError == null ? Status.SIGNING_IN : Status.SIGNED_OUT;
	}

	public boolean available() {
		return api != null;
	}

	/** Online features may be used: built with a service, turned on and the notice accepted. */
	public boolean active() {
		return api != null && cfg().onlineEnabled && cfg().onlineNoticeAccepted >= NOTICE_VERSION;
	}

	public boolean signedIn() {
		return active() && state != null && state.hasToken(currentUuid());
	}

	public boolean isAdmin() {
		return signedIn() && state.admin;
	}

	public String name() {
		return state == null ? null : state.name;
	}

	public String uuid() {
		return state == null ? null : state.uuid;
	}

	public String signInError() {
		return signInError;
	}

	public String serviceUrl() {
		return baseUrl;
	}

	/** Changes whenever online data (friends, presence, sign-in) changes, so pages know to redraw or reload. */
	public int revision() {
		return revision;
	}

	public boolean needsNotice() {
		return api != null && cfg().onlineEnabled && cfg().onlineNoticeAccepted < NOTICE_VERSION;
	}

	/** The notice is pending and has not been shown during this launch yet. */
	public boolean noticeDue() {
		return needsNotice() && !noticeShownThisLaunch;
	}

	/** True the first time it is asked during a launch while the notice is still pending. */
	public boolean takeNoticeTurn() {
		if (!needsNotice() || noticeShownThisLaunch) {
			return false;
		}
		noticeShownThisLaunch = true;
		return true;
	}

	public void acceptNotice() {
		cfg().onlineNoticeAccepted = NOTICE_VERSION;
		cfg().onlineEnabled = true;
		siq.config.changed();
		nextSignInAt = 0;
		revision++;
	}

	private static String currentUuid() {
		UUID id = MinecraftClient.getInstance().getSession().getUuidOrNull();
		return id == null ? null : id.toString();
	}

	public void tick(MinecraftClient client) {
		if (state == null || !active()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!launchSent) {
			launchSent = true;
			JsonObject body = new JsonObject();
			body.addProperty("installId", state.installId);
			body.addProperty("modVersion", siq.modVersion);
			body.addProperty("mcVersion", siq.mcVersion);
			api.post("/v1/telemetry/launch", body, null, JsonObject.class).exceptionally(e -> {
				SiqLog.debug("Launch count not sent: {}", ApiClient.message(e));
				return null;
			});
		}
		if (!signedIn()) {
			if (live.connected()) {
				live.close();
			}
			if (!signingIn && now >= nextSignInAt) {
				signIn();
			}
			return;
		}
		if (!sessionStarted) {
			// Signed in with a token saved by an earlier launch.
			onSignedIn();
		}
		live.tick(api, state.token, now);
		if (friendsReloadAt > 0 && now >= friendsReloadAt && !friendsLoading) {
			friendsReloadAt = 0;
			loadFriends();
		}
		boolean inWorld = siq.tracker.visit() != null;
		if (wasInWorld && !inWorld) {
			nextSyncAt = Math.min(nextSyncAt, now + 5_000);
		}
		wasInWorld = inWorld;
		if (now >= nextActivityCheckAt) {
			nextActivityCheckAt = now + ACTIVITY_CHECK_MS;
			if (hasFriends() && !activity().equals(lastActivity)) {
				nextPresenceAt = 0;
			}
		}
		if (now >= nextPresenceAt && !presenceInFlight) {
			sendPresence(true);
		}
		if (now >= nextSyncAt && !syncInFlight && siq.isReady()) {
			sync();
		}
	}

	private void signIn() {
		MinecraftClient mc = MinecraftClient.getInstance();
		Session session = mc.getSession();
		UUID id = session.getUuidOrNull();
		String name = session.getUsername();
		if (id == null || name == null) {
			signInFailed("Minecraft has no account signed in");
			return;
		}
		String uuid = id.toString();
		if (state.hasToken(uuid)) {
			onSignedIn();
			return;
		}
		if (!uuid.equals(state.uuid)) {
			state.clearToken();
		}
		signingIn = true;
		revision++;
		api.post("/v1/auth/challenge", Map.of("uuid", uuid), null, Api.Challenge.class)
			// The profile key is the one Minecraft already uses for secure chat; fetch it on the client thread.
			.thenComposeAsync(challenge -> mc.getProfileKeys().fetchKeyPair().thenApply(keys -> signInBody(uuid, name, challenge.nonce, keys.orElse(null))),
				SessionIQ.mainThread())
			.thenCompose(body -> api.post("/v1/auth/verify", body, null, Api.Verified.class))
			.whenCompleteAsync((verified, error) -> {
				signingIn = false;
				if (error != null) {
					signInFailed(ApiClient.message(error));
					return;
				}
				state.uuid = verified.uuid;
				state.name = verified.name;
				state.token = verified.token;
				state.tokenExpiresAt = verified.expiresAt;
				state.admin = verified.admin;
				state.save(siq.paths.online);
				SiqLog.info("SessionIQ signed in to online features as {}", verified.name);
				onSignedIn();
			}, SessionIQ.mainThread());
	}

	/**
	 * Proof of account ownership without any Minecraft login token: the nonce is signed with the
	 * private half of the player key Mojang issued for secure chat, and the public key is sent
	 * with Mojang's signature over (UUID, expiry, key) so the server can check who it belongs to.
	 */
	private JsonObject signInBody(String uuid, String name, String nonce, PlayerKeyPair keys) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("name", name);
		body.addProperty("nonce", nonce);
		if (keys == null) {
			if (localService()) {
				// Testing against a backend on this computer (ALLOW_DEV_AUTH) with an offline dev account.
				return body;
			}
			throw new CompletionException(new ApiClient.ApiException(401, "Minecraft has no signed profile key for this account. Online features need a Microsoft "
				+ "account signed in through the official launcher or another launcher that logs in to Microsoft."));
		}
		try {
			PlayerPublicKey.PublicKeyData data = keys.publicKey().data();
			Signature signer = Signature.getInstance("SHA256withRSA");
			signer.initSign(keys.privateKey());
			signer.update(("SessionIQ sign-in\n" + uuid + "\n" + nonce).getBytes(StandardCharsets.UTF_8));
			Base64.Encoder b64 = Base64.getEncoder();
			body.addProperty("publicKey", b64.encodeToString(data.key().getEncoded()));
			body.addProperty("keyExpiresAt", data.expiresAt().toEpochMilli());
			body.addProperty("keySignature", b64.encodeToString(data.keySignature()));
			body.addProperty("signature", b64.encodeToString(signer.sign()));
			return body;
		} catch (GeneralSecurityException e) {
			throw new CompletionException(new ApiClient.ApiException(-1, "Could not sign in: " + e.getMessage()));
		}
	}

	private boolean localService() {
		String host = java.net.URI.create(baseUrl).getHost();
		return "localhost".equals(host) || "127.0.0.1".equals(host);
	}

	private void signInFailed(String message) {
		signingIn = false;
		signInError = message;
		nextSignInAt = System.currentTimeMillis() + SIGN_IN_BACKOFF_MS[Math.min(signInFailures, SIGN_IN_BACKOFF_MS.length - 1)];
		signInFailures++;
		revision++;
		SiqLog.info("SessionIQ online sign-in failed: {}", message);
	}

	/** Lets the player retry signing in right away (Settings › Online). */
	public void retrySignIn() {
		signInError = null;
		signInFailures = 0;
		nextSignInAt = 0;
		revision++;
	}

	private void onSignedIn() {
		sessionStarted = true;
		signInError = null;
		signInFailures = 0;
		lastPrivacyPushed = null;
		lastSyncFingerprint = null;
		nextPresenceAt = 0;
		nextSyncAt = 0;
		revision++;
		pushPrivacy();
		loadFriends();
	}

	// Live events ---------------------------------------------------------------------------

	public boolean liveConnected() {
		return live.connected();
	}

	private void onLiveConnected() {
		// Catch up on anything that changed while disconnected.
		friendsReloadAt = System.currentTimeMillis();
		revision++;
	}

	private void onLiveEvent(String raw) {
		JsonObject event;
		try {
			event = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
		} catch (Exception e) {
			return;
		}
		String type = event.has("t") ? event.get("t").getAsString() : "";
		switch (type) {
			case "friends" -> friendsReloadAt = System.currentTimeMillis() + 250;
			case "message" -> onLiveMessage(text(event, "from"), text(event, "name"), text(event, "text"));
			case "online" -> {
				if (event.has("count") && event.get("count").isJsonPrimitive()) {
					onlineNow = event.get("count").getAsInt();
					revision++;
				}
			}
			default -> {
			}
		}
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	private void onLiveMessage(String from, String name, String text) {
		messageSignals.merge(from, 1, Integer::sum);
		boolean viewing = from.equals(viewingChat) && System.currentTimeMillis() - viewingChatAt < 1_000;
		if (!viewing) {
			Map<String, Integer> unread = new HashMap<>(presence.unread);
			unread.merge(from, 1, Integer::sum);
			presence.unread = unread;
			Api.Friend f = friend(from);
			if (f != null) {
				f.unread++;
			}
			if (cfg().friendNotifications) {
				String clean = text.replace("§", "");
				String preview = clean.length() > 40 ? clean.substring(0, 39) + "…" : clean;
				String who = name.isEmpty() ? (friendName(from) != null ? friendName(from) : "Friend") : name;
				SystemToast.show(MinecraftClient.getInstance().getToastManager(), MESSAGE_TOAST, Text.literal(who), Text.literal(preview));
			}
		}
		revision++;
	}

	/**
	 * Admin only: players connected right now, pushed by the server whenever someone connects or
	 * disconnects. Null until the first push (or while the live connection is down).
	 */
	public Integer onlineNow() {
		return live.connected() ? onlineNow : null;
	}

	/** Changes whenever a new message from this friend arrives, so an open chat reloads at once. */
	public int messageSignal(String friendUuid) {
		return messageSignals.getOrDefault(friendUuid, 0);
	}

	/** Called every frame by an open chat, so messages there do not also pop up as notifications. */
	public void viewingChat(String friendUuid) {
		viewingChat = friendUuid;
		viewingChatAt = System.currentTimeMillis();
	}

	private void onUnauthorized() {
		live.close();
		sessionStarted = false;
		if (state != null && state.token != null) {
			state.clearToken();
			state.save(siq.paths.online);
			nextSignInAt = 0;
			revision++;
		}
	}

	private <T> CompletableFuture<T> handleAuth(CompletableFuture<T> f) {
		return f.whenCompleteAsync((v, e) -> {
			if (e != null && ApiClient.status(e) == 401) {
				onUnauthorized();
			}
		}, SessionIQ.mainThread());
	}

	private static <T> CompletableFuture<T> failed(String message) {
		return CompletableFuture.failedFuture(new ApiClient.ApiException(-1, message));
	}

	/** Authenticated GET; fails with a readable message when not signed in. */
	public <T> CompletableFuture<T> get(String path, Class<T> type) {
		if (!signedIn()) {
			return failed(notSignedInMessage());
		}
		return handleAuth(api.get(path, state.token, type));
	}

	public <T> CompletableFuture<T> post(String path, Object body, Class<T> type) {
		if (!signedIn()) {
			return failed(notSignedInMessage());
		}
		return handleAuth(api.post(path, body, state.token, type));
	}

	public String notSignedInMessage() {
		return switch (status()) {
			case UNAVAILABLE -> "Online features are not available in this build of SessionIQ.";
			case DISABLED -> "Online features are turned off in Settings › Online.";
			case NEEDS_NOTICE -> "Review the online features notice first (Settings › Online).";
			case SIGNING_IN -> "Signing in…";
			case SIGNED_OUT -> signInError != null ? signInError : "Not signed in.";
			case SIGNED_IN -> "";
		};
	}

	public CompletableFuture<Api.Global> global() {
		if (!active()) {
			return failed(notSignedInMessage());
		}
		return api.get("/v1/global", signedIn() ? state.token : null, Api.Global.class);
	}

	public CompletableFuture<JsonObject> suggest(String category, String text, boolean includeName) {
		if (api == null || !cfg().onlineEnabled) {
			return failed(api == null ? "Suggestions need the online service, which is not available in this build." : "Online features are turned off in Settings › Online.");
		}
		JsonObject body = new JsonObject();
		body.addProperty("installId", state.installId);
		body.addProperty("category", category);
		body.addProperty("text", text);
		body.addProperty("modVersion", siq.modVersion);
		body.addProperty("includeName", includeName && signedIn());
		return api.post("/v1/suggestions", body, includeName && signedIn() ? state.token : null, JsonObject.class);
	}

	// Presence -----------------------------------------------------------------------------

	/** What friends may see the player doing; respects the friend privacy settings. */
	String activity() {
		VisitRecord v = siq.tracker.visit();
		if (v == null) {
			return "menu";
		}
		if (!v.isMultiplayer()) {
			return "singleplayer";
		}
		String host = VisitRecord.KIND_MULTIPLAYER.equals(v.kind) ? PublicHost.of(v.host) : null;
		return host == null ? "private" : "server:" + host;
	}

	JsonObject presenceBody(boolean online) {
		JsonObject body = new JsonObject();
		boolean show = online && cfg().friendsShowOnline;
		body.addProperty("online", show);
		if (show) {
			String activity = cfg().friendsShowServer ? activity() : "hidden";
			if (activity.startsWith("server:")) {
				body.addProperty("activity", "server");
				body.addProperty("server", activity.substring(7));
			} else {
				body.addProperty("activity", activity);
			}
		}
		return body;
	}

	private boolean hasFriends() {
		return friends != null && !friends.friends.isEmpty();
	}

	private void sendPresence(boolean online) {
		presenceInFlight = true;
		lastActivity = activity();
		nextPresenceAt = System.currentTimeMillis() + (hasFriends() ? PRESENCE_INTERVAL_MS : PRESENCE_IDLE_INTERVAL_MS);
		post("/v1/presence", presenceBody(online), Api.Presence.class).whenCompleteAsync((p, e) -> {
			presenceInFlight = false;
			if (p != null) {
				onPresence(p);
			}
		}, SessionIQ.mainThread());
	}

	private void onPresence(Api.Presence p) {
		Map<String, Integer> before = presence.unread;
		int requestsBefore = presence.requests;
		presence = p;
		revision++;
		if (!cfg().friendNotifications) {
			return;
		}
		boolean newMessages = false;
		for (Map.Entry<String, Integer> e : p.unread.entrySet()) {
			if (e.getValue() > before.getOrDefault(e.getKey(), 0)) {
				newMessages = true;
			}
		}
		if (newMessages || p.requests > requestsBefore) {
			// Refresh the friends list so names are known, then notify.
			loadFriends().thenRunAsync(() -> notifyPlayer(before, requestsBefore), SessionIQ.mainThread());
		}
	}

	private void notifyPlayer(Map<String, Integer> before, int requestsBefore) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (presence.requests > requestsBefore) {
			int n = presence.requests - requestsBefore;
			SystemToast.show(mc.getToastManager(), TOAST, Text.literal("SessionIQ"), Text.literal(n == 1 ? "New friend request" : n + " new friend requests"));
			return;
		}
		for (Map.Entry<String, Integer> e : presence.unread.entrySet()) {
			if (e.getValue() > before.getOrDefault(e.getKey(), 0)) {
				String who = friendName(e.getKey());
				SystemToast.show(mc.getToastManager(), TOAST, Text.literal("SessionIQ · " + (who == null ? "Friend" : who)), Text.literal("sent you a message"));
				return;
			}
		}
	}

	public int unreadTotal() {
		int n = 0;
		for (int v : presence.unread.values()) {
			n += v;
		}
		return n;
	}

	public int pendingRequests() {
		return presence.requests;
	}

	/** Called when a conversation was read, so the unread badge clears without waiting for the next heartbeat. */
	public void markRead(String friendUuid) {
		if (presence.unread.containsKey(friendUuid)) {
			Map<String, Integer> copy = new HashMap<>(presence.unread);
			copy.remove(friendUuid);
			presence.unread = copy;
			revision++;
		}
	}

	// Stats --------------------------------------------------------------------------------

	private boolean uploadsStats() {
		return cfg().shareGlobalStats || cfg().friendsShowStats;
	}

	/** Exactly what the next stats upload contains. */
	public StatsSnapshot currentSnapshot() {
		return StatsSnapshot.compute(siq.sessions.snapshot(), cfg().shareGlobalStats);
	}

	/** Uploads changed stats soon (at most once a minute), e.g. when Global Statistics is opened. */
	public void requestSync() {
		if (signedIn() && System.currentTimeMillis() - lastSyncStartAt > 60_000) {
			nextSyncAt = 0;
		}
	}

	/** Changes after every finished stats upload attempt, so pages showing stats know to reload. */
	public int syncGeneration() {
		return syncGeneration;
	}

	private void sync() {
		nextSyncAt = System.currentTimeMillis() + SYNC_INTERVAL_MS;
		lastSyncStartAt = System.currentTimeMillis();
		if (!uploadsStats()) {
			return;
		}
		syncInFlight = true;
		List<SessionRecord> history = siq.sessions.snapshot();
		boolean servers = cfg().shareGlobalStats;
		siq.storage.submitQuery(() -> StatsSnapshot.compute(history, servers)).thenComposeAsync(snap -> {
			String fp = snap.fingerprint();
			if (fp.equals(lastSyncFingerprint)) {
				return CompletableFuture.completedFuture(null);
			}
			return post("/v1/me/sync", snap, JsonObject.class).thenApply(r -> fp);
		}, SessionIQ.mainThread()).whenCompleteAsync((fp, e) -> {
			syncInFlight = false;
			if (e != null) {
				SiqLog.debug("Stats sync failed: {}", ApiClient.message(e));
				nextSyncAt = System.currentTimeMillis() + 60_000;
			} else if (fp != null) {
				lastSyncFingerprint = fp;
				SiqLog.debug("Stats synced");
			}
			syncGeneration++;
		}, SessionIQ.mainThread());
	}

	/** Pushes the privacy settings when they changed; called on sign-in and when settings change. */
	public void onConfigChanged() {
		boolean nowActive = active();
		if (wasActive && !nowActive) {
			onDisabled();
		}
		wasActive = nowActive;
		if (!nowActive) {
			return;
		}
		if (signedIn()) {
			pushPrivacy();
			nextPresenceAt = 0;
			nextSyncAt = Math.min(nextSyncAt, System.currentTimeMillis() + 2_000);
		}
	}

	private JsonObject privacyBody() {
		JsonObject p = new JsonObject();
		p.addProperty("shareStats", cfg().shareGlobalStats);
		p.addProperty("showOnline", cfg().friendsShowOnline);
		p.addProperty("showServer", cfg().friendsShowServer);
		p.addProperty("showStats", cfg().friendsShowStats);
		p.addProperty("allowChat", cfg().friendsAllowChat);
		return p;
	}

	private void pushPrivacy() {
		JsonObject body = privacyBody();
		String key = body.toString();
		if (key.equals(lastPrivacyPushed)) {
			return;
		}
		lastPrivacyPushed = key;
		post("/v1/me/privacy", body, Api.Profile.class).whenCompleteAsync((p, e) -> {
			if (e != null) {
				lastPrivacyPushed = null;
			} else {
				state.admin = p.admin;
				lastSyncFingerprint = null;
				revision++;
			}
		}, SessionIQ.mainThread());
	}

	// Friends ------------------------------------------------------------------------------

	public Api.Friends friends() {
		return friends;
	}

	public String friendsError() {
		return friendsError;
	}

	public boolean friendsLoading() {
		return friendsLoading;
	}

	/** Reloads the friends list when it is older than maxAgeMs. Safe to call every frame. */
	public void ensureFriends(long maxAgeMs) {
		if (signedIn() && !friendsLoading && System.currentTimeMillis() - friendsAt > maxAgeMs) {
			loadFriends();
		}
	}

	public CompletableFuture<Api.Friends> loadFriends() {
		friendsLoading = true;
		return get("/v1/friends", Api.Friends.class).whenCompleteAsync((f, e) -> {
			friendsLoading = false;
			friendsAt = System.currentTimeMillis();
			if (f != null) {
				friends = f;
				friendsError = null;
				Map<String, Integer> unread = new HashMap<>();
				int incoming = f.incoming.size();
				for (Api.Friend fr : f.friends) {
					if (fr.unread > 0) {
						unread.put(fr.uuid, fr.unread);
					}
				}
				presence.unread = unread;
				presence.requests = incoming;
				long soon = System.currentTimeMillis() + PRESENCE_INTERVAL_MS;
				if (hasFriends() && nextPresenceAt > soon) {
					nextPresenceAt = soon;
				}
			} else {
				friendsError = ApiClient.message(e);
			}
			revision++;
		}, SessionIQ.mainThread());
	}

	public Api.Friend friend(String uuid) {
		if (friends == null) {
			return null;
		}
		for (Api.Friend f : friends.friends) {
			if (f.uuid.equals(uuid)) {
				return f;
			}
		}
		return null;
	}

	private String friendName(String uuid) {
		Api.Friend f = friend(uuid);
		return f == null ? null : f.name;
	}

	public CompletableFuture<Api.RequestResult> requestFriend(String name) {
		return post("/v1/friends/request", Map.of("name", name), Api.RequestResult.class).thenApplyAsync(r -> {
			loadFriends();
			return r;
		}, SessionIQ.mainThread());
	}

	/** Runs a friends action (respond, cancel, remove, block, unblock) and reloads the list. */
	public CompletableFuture<JsonObject> friendAction(String action, Map<String, Object> body) {
		return post("/v1/friends/" + action, body, JsonObject.class).thenApplyAsync(r -> {
			loadFriends();
			return r;
		}, SessionIQ.mainThread());
	}

	// Data controls ------------------------------------------------------------------------

	/**
	 * Deletes the account's online data and the anonymous install record, then turns online
	 * features off so nothing is uploaded again until the player turns them back on.
	 */
	public CompletableFuture<Void> deleteOnlineData() {
		if (api == null) {
			return failed("Online features are not available in this build.");
		}
		CompletableFuture<?> account = signedIn() ? post("/v1/me/delete", null, JsonObject.class) : CompletableFuture.completedFuture(null);
		String installId = state.installId;
		return account.thenCompose(a -> api.post("/v1/telemetry/forget", Map.of("installId", installId), null, JsonObject.class)).thenAcceptAsync(r -> {
			state.clearToken();
			state.uuid = null;
			state.name = null;
			state.installId = UUID.randomUUID().toString();
			state.save(siq.paths.online);
			friends = null;
			presence = new Api.Presence();
			launchSent = false;
			cfg().onlineEnabled = false;
			siq.config.changed();
			revision++;
		}, SessionIQ.mainThread());
	}

	/** Called when online features are turned off: tells friends the player went offline. */
	private void onDisabled() {
		live.close();
		if (state != null && state.token != null && api != null) {
			api.post("/v1/presence", presenceBody(false), state.token, JsonObject.class).exceptionally(e -> null);
		}
		friends = null;
		presence = new Api.Presence();
		launchSent = false;
		sessionStarted = false;
		revision++;
	}

	public void shutdown() {
		live.close();
		if (signedIn()) {
			try {
				api.post("/v1/presence", presenceBody(false), state.token, JsonObject.class).get(1500, TimeUnit.MILLISECONDS);
			} catch (Exception ignored) {
			}
		}
		worker.shutdownNow();
	}
}
