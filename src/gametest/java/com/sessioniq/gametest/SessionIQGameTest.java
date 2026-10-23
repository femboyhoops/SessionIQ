package com.sessioniq.gametest;

import com.google.gson.JsonParser;
import com.sessioniq.SessionIQ;
import com.sessioniq.analytics.ChatSearch;
import com.sessioniq.analytics.DateRange;
import com.sessioniq.analytics.ServerAggregate;
import com.sessioniq.analytics.StatsEngine;
import com.sessioniq.backup.BackupService;
import com.sessioniq.export.ExportService.Format;
import com.sessioniq.gui.SiqScreen;
import com.sessioniq.gui.pages.ChatPage;
import com.sessioniq.gui.pages.SessionDetailPage;
import com.sessioniq.gui.pages.SessionsPage;
import com.sessioniq.gui.pages.ServerDetailPage;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MarkerRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.theme.Theme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class SessionIQGameTest implements FabricClientGameTest {
	private static final String SECRET = "secretpassword123";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		current = ctx;
		String phase = System.getProperty("sessioniq.gametest.phase", "full");
		log("phase " + phase + ", data in " + System.getProperty("sessioniq.dataDir"));
		ctx.waitFor(c -> SessionIQ.get().isReady(), 1200);
		production = "prod".equals(phase);
		switch (phase) {
			case "crash" -> crashPhase(ctx);
			case "recover" -> recoverPhase(ctx);
			case "online" -> onlinePhase(ctx);
			default -> fullPhase(ctx);
		}
	}

	// Online phase: runs against a local backend (see build.gradle) and drives a second player through its API.

	private static final String API = System.getProperty("sessioniq.api", "http://127.0.0.1:8787");
	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();

	private static com.google.gson.JsonObject http(String method, String path, Object body, String token) {
		try {
			var b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(API + path)).header("content-type", "application/json");
			if (token != null) {
				b.header("authorization", "Bearer " + token);
			}
			b.method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
				: java.net.http.HttpRequest.BodyPublishers.ofString(com.sessioniq.util.Json.COMPACT.toJson(body)));
			var res = HTTP.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new AssertionError(method + " " + path + " -> " + res.statusCode() + " " + res.body());
			}
			return JsonParser.parseString(res.body()).getAsJsonObject();
		} catch (java.io.IOException | InterruptedException e) {
			throw new AssertionError("backend not reachable at " + API + "; start it with `npm run dev` in backend/", e);
		}
	}

	private static String[] otherPlayer(String name) {
		String uuid = java.util.UUID.randomUUID().toString();
		String nonce = http("POST", "/v1/auth/challenge", java.util.Map.of("uuid", uuid), null).get("nonce").getAsString();
		String token = http("POST", "/v1/auth/verify", java.util.Map.of("uuid", uuid, "name", name, "nonce", nonce), null).get("token").getAsString();
		return new String[]{uuid, token};
	}

	private void onlinePhase(ClientGameTestContext ctx) {
		ctx.runOnClient(c -> {
			siq().config.get().onlineNoticeAccepted = 0;
			siq().config.get().onlineEnabled = true;
			siq().config.changed();
		});
		check(on(ctx, c -> siq().online.status() == com.sessioniq.online.OnlineService.Status.NEEDS_NOTICE), "online features wait for the notice");
		ctx.waitFor(c -> c.currentScreen instanceof SiqScreen, 400);
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).hasModal()), "the notice opens on the title screen at first launch");
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_notice");
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).openModal(new com.sessioniq.gui.WhatsSentModal()));
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_whats_sent");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(5);
		check(on(ctx, c -> siq().online.status() == com.sessioniq.online.OnlineService.Status.NEEDS_NOTICE), "closing the notice without accepting uploads nothing");

		ctx.runOnClient(c -> siq().online.acceptNotice());
		ctx.waitFor(c -> siq().online.signedIn(), 600);
		check(true, "signed in as " + on(ctx, c -> siq().online.name()));
		ctx.waitFor(c -> siq().online.isAdmin(), 200);

		String[] friend = otherPlayer("Friendly");
		http("POST", "/v1/me/sync", java.util.Map.of("playtimeMs", 54_000_000L, "sessions", 40, "totalJoins", 88, "serversJoined", 3, "topServer", "mc.hypixel.net",
			"topServerMs", 40_000_000L, "servers", List.of(java.util.Map.of("host", "mc.hypixel.net", "playtimeMs", 40_000_000L, "joins", 60),
				java.util.Map.of("host", "play.cubecraft.net", "playtimeMs", 14_000_000L, "joins", 28))), friend[1]);
		http("POST", "/v1/friends/request", java.util.Map.of("name", "SiqTester"), friend[1]);

		ctx.setScreen(() -> new SiqScreen(null));
		ctx.waitForScreen(SiqScreen.class);
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("friends"));
		ctx.waitFor(c -> siq().online.friends() != null && !siq().online.friends().incoming.isEmpty(), 400);
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_friends_request");
		await(on(ctx, c -> siq().online.friendAction("respond", java.util.Map.of("uuid", friend[0], "accept", true))));
		ctx.waitFor(c -> siq().online.liveConnected(), 200);
		check(true, "live connection is open");

		// Sender side: the list updates the moment the other player accepts, with no reload.
		String[] pal = otherPlayer("Pal");
		await(on(ctx, c -> siq().online.requestFriend("Pal")));
		http("POST", "/v1/friends/respond", java.util.Map.of("uuid", "5e551017-0000-4000-8000-000000000001", "accept", true), pal[1]);
		ctx.waitFor(c -> siq().online.friend(pal[0]) != null, 100);
		check(true, "an accepted request appears on the sender's list instantly");

		http("POST", "/v1/presence", java.util.Map.of("online", true, "activity", "server", "server", "londs.mcpvp.com"), friend[1]);
		ctx.waitFor(c -> siq().online.friend(friend[0]) != null && "server:mcpvp.com".equals(siq().online.friend(friend[0]).activity), 100);
		check(true, "a friend joining a server shows instantly, grouped under the main domain");
		http("POST", "/v1/chat/send", java.util.Map.of("to", "5e551017-0000-4000-8000-000000000001", "text", "hey! want to play bedwars later?"), friend[1]);
		ctx.waitFor(c -> siq().online.friend(friend[0]).unread == 1, 100);
		check(true, "a new message arrives instantly and is counted as unread");
		ctx.waitTicks(5);
		ctx.takeScreenshot("o_message_toast");
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_friends_list");

		for (int i = 1; i <= 14; i++) {
			http("POST", "/v1/chat/send", java.util.Map.of("to", "5e551017-0000-4000-8000-000000000001", "text",
				"message number " + i + ", long enough to wrap onto a second line in the chat so the history is taller than the box"), friend[1]);
		}
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).push(new com.sessioniq.gui.pages.FriendDetailPage((SiqScreen) c.currentScreen, friend[0], "Friendly")));
		ctx.waitTicks(60);
		String chatId = "friend#chat" + friend[0];
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).ui.scrollOffset(chatId) > 0 && ((SiqScreen) c.currentScreen).ui.nearBottom(chatId, 30)),
			"a long chat opens at the newest messages, not the top");
		ctx.takeScreenshot("o_chat_opens_at_bottom");
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.field("friend#input" + friend[0]).set("sure, after dinner"));
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.focus("friend#input" + friend[0]));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(60);
		com.google.gson.JsonObject convo = http("GET", "/v1/chat?with=5e551017-0000-4000-8000-000000000001", null, friend[1]);
		var all = convo.getAsJsonArray("messages");
		var last = all.get(all.size() - 1).getAsJsonObject();
		check("sure, after dinner".equals(last.get("text").getAsString()) && !last.get("mine").getAsBoolean(), "the reply typed in the chat box reached the friend");
		ctx.takeScreenshot("o_friend_detail");

		ctx.runOnClient(c -> siq().online.onConfigChanged());
		ctx.waitTicks(100);
		HTTP.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(API + "/cdn-cgi/handler/scheduled")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).join();
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("global"));
		ctx.waitFor(c -> {
			var page = ((SiqScreen) c.currentScreen).current();
			return page instanceof com.sessioniq.gui.pages.GlobalPage;
		}, 100);
		ctx.waitTicks(80);
		ctx.takeScreenshot("o_global");
		com.google.gson.JsonObject global = http("GET", "/v1/global", null, null);
		check(global.getAsJsonArray("topPlayers").toString().contains("Friendly"), "global statistics list players");

		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("suggestions"));
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.field("suggest#text").set("Could the timeline show which friends were online at the same time?"));
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_suggestions");
		await(on(ctx, c -> siq().online.suggest("feature", "Could the timeline show which friends were online at the same time?", true)));

		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("admin"));
		ctx.waitFor(c -> siq().online.onlineNow() != null, 200);
		int before = on(ctx, c -> siq().online.onlineNow());
		check(before >= 1, "the admin sees a live online count (" + before + ")");
		java.net.http.WebSocket visitor = HTTP.newWebSocketBuilder().header("authorization", "Bearer " + friend[1])
			.buildAsync(java.net.URI.create(API.replaceFirst("^http", "ws") + "/v1/live"), new java.net.http.WebSocket.Listener() {
			}).join();
		ctx.waitFor(c -> siq().online.onlineNow() == before + 1, 100);
		check(true, "the count goes up the moment another player connects");
		ctx.waitTicks(20);
		ctx.takeScreenshot("o_admin");
		visitor.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye").join();
		ctx.waitFor(c -> siq().online.onlineNow() == before, 100);
		check(true, "and goes back down when they disconnect");
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.scrollTo("admin#scroll", 600));
		ctx.waitTicks(30);
		ctx.takeScreenshot("o_admin_inbox");

		ctx.runOnClient(c -> com.sessioniq.gui.OnlineGate.openOnlineSettings((SiqScreen) c.currentScreen));
		ctx.waitTicks(30);
		ctx.takeScreenshot("o_settings_online");

		ctx.runOnClient(c -> {
			siq().config.get().friendsShowServer = false;
			siq().config.get().friendsShowStats = false;
			siq().config.changed();
		});
		ctx.waitTicks(60);
		com.google.gson.JsonObject seen = http("GET", "/v1/friends", null, friend[1]);
		var me = seen.getAsJsonArray("friends").get(0).getAsJsonObject();
		ctx.runOnClient(c -> {
			siq().config.get().friendsShowServer = true;
			siq().config.get().friendsShowStats = true;
			siq().config.changed();
		});
		check(me.get("activity").isJsonNull() && me.get("stats").isJsonNull(), "privacy settings are pushed: friends no longer see server or stats");
		ctx.setScreen(() -> null);
		log("online phase passed");
	}

	private static SessionIQ siq() {
		return SessionIQ.get();
	}

	private static <T> T on(ClientGameTestContext ctx, Function<MinecraftClient, T> f) {
		return ctx.computeOnClient(f::apply);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("SessionIQ check failed: " + message);
		}
		log("ok: " + message);
	}

	private static void log(String s) {
		System.out.println("[SessionIQ-GameTest] " + s);
	}

	private static ClientGameTestContext current;
	private static boolean production;

	private static <T> T await(CompletableFuture<T> f) {
		current.waitFor(c -> f.isDone(), 1200);
		try {
			return f.join();
		} catch (Exception e) {
			throw new AssertionError("future failed", e);
		}
	}

	private static SessionRecord current(ClientGameTestContext ctx) {
		return on(ctx, c -> {
			siq().tracker.updateLive();
			return com.sessioniq.util.Json.copy(siq().tracker.session(), SessionRecord.class);
		});
	}

	private static DisconnectRecord lastDisconnect(ClientGameTestContext ctx) {
		ctx.waitTicks(25);
		SessionRecord s = current(ctx);
		return s.disconnects.isEmpty() ? null : s.disconnects.getLast();
	}

	private static List<ChatRecord> chatOf(ClientGameTestContext ctx, String sessionId) {
		await(on(ctx, c -> {
			siq().chat.flush();
			return siq().storage.submitIo(() -> null);
		}));
		return siq().chat.readSession(sessionId);
	}

	private static void sendChat(ClientGameTestContext ctx, String text) {
		ctx.getInput().pressKey(GLFW.GLFW_KEY_T);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(text);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(10);
	}

	private static void disconnectViaPauseMenu(ClientGameTestContext ctx) {
		ctx.setScreen(() -> new GameMenuScreen(true));
		ctx.waitForScreen(GameMenuScreen.class);
		ctx.clickScreenButton("menu.disconnect");
		ctx.waitFor(c -> c.world == null, 400);
		ctx.waitTicks(20);
	}

	private static void waitForVisit(ClientGameTestContext ctx) {
		ctx.waitFor(c -> siq().tracker.visit() != null, 400);
	}

	private static void shootAllSections(ClientGameTestContext ctx, String prefix) {
		ctx.setScreen(() -> new SiqScreen(null));
		ctx.waitForScreen(SiqScreen.class);
		for (SiqScreen.NavItem item : SiqScreen.NAV) {
			ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection(item.key()));
			ctx.waitTicks(30);
			ctx.takeScreenshot(prefix + "_" + item.key());
		}
	}

	private static void setTab(ClientGameTestContext ctx, Object page, String field, int value) {
		ctx.runOnClient(c -> {
			try {
				Field f = page.getClass().getDeclaredField(field);
				f.setAccessible(true);
				f.setInt(page, value);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	private void fullPhase(ClientGameTestContext ctx) {
		ctx.runOnClient(c -> {
			siq().config.get().memoryIntervalSec = 5;
			siq().config.get().checkpointSeconds = 10;
			siq().config.changed();
		});
		SessionRecord start = current(ctx);
		check(start != null && start.isActive(), "a session is recording after launch");
		check(start.mcVersion != null && start.mcVersion.startsWith("1.21.11"), "Minecraft version captured: " + start.mcVersion);
		String sessionId = start.id;
		ctx.runOnClient(c -> siq().tracker.checkpoint());
		await(on(ctx, c -> siq().storage.submitIo(() -> null)));
		Path sessionFile = siq().paths.sessionFile(sessionId, start.startedAt);
		check(Files.exists(sessionFile), "session file written early: " + sessionFile.getFileName());

		shootAllSections(ctx, "a_menus");
		check(com.sessioniq.track.HookCheck.missing().isEmpty(), "every game hook was applied: " + com.sessioniq.track.HookCheck.missing());

		singleplayer(ctx, sessionId);
		if (!production) {
			multiplayer(ctx);
		}
		failedConnection(ctx);
		verifyPersistence(ctx, sessionId);
		exportsAndBackups(ctx);
		guiInteraction(ctx, sessionId);
		log("full phase complete");
	}

	private void singleplayer(ClientGameTestContext ctx, String sessionId) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			waitForVisit(ctx);
			VisitRecord v = on(ctx, c -> siq().tracker.visit());
			check(!v.isMultiplayer() && v.serverKey.startsWith("sp:"), "single-player world tracked as a world: " + v.serverKey);

			sendChat(ctx, "hello from test");
			sendChat(ctx, "/tell @s " + SECRET);
			sendChat(ctx, "/me waves " + SECRET);
			sp.getServer().runCommand("tellraw @a {\"text\":\"Gold system line\",\"color\":\"gold\",\"bold\":true}");
			sp.getServer().runCommand("say announcement from console");
			ctx.waitTicks(20);

			List<ChatRecord> chat = chatOf(ctx, sessionId);
			check(chat.stream().anyMatch(r -> r.text.contains("hello from test") && ChatCategory.CHAT.id.equals(r.category) && r.sender != null),
				"own chat message archived with sender");
			ChatRecord gold = chat.stream().filter(r -> r.text.contains("Gold system line")).findFirst().orElse(null);
			check(gold != null && gold.isSystem(), "system message archived as system");
			check(gold.format != null && gold.format.getFirst()[1] == 0xFFAA00 && (gold.format.getFirst()[2] & 1) == 1, "colour and bold preserved");
			check(chat.stream().anyMatch(r -> r.text.contains("announcement from console") && ChatCategory.ANNOUNCEMENT.id.equals(r.category)),
				"/say from the console archived as an announcement");
			check(chat.stream().noneMatch(r -> r.text.contains(SECRET)), "outgoing command arguments never archived");

			ctx.setScreen(() -> new GameMenuScreen(true));
			ctx.waitTicks(50);
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			check(current(ctx).visits.getLast().pausedMs >= 1500, "paused time measured: " + current(ctx).visits.getLast().pausedMs + " ms");

			sp.getServer().runCommand("kill @a");
			ctx.waitForScreen(DeathScreen.class);
			ctx.waitTicks(25);
			ctx.clickScreenButton("deathScreen.respawn");
			ctx.waitForScreen(null);
			ctx.waitTicks(10);
			check(current(ctx).markers.stream().anyMatch(m -> MarkerRecord.TYPE_DEATH.equals(m.type) && m.text != null), "death recorded with its message");

			shootAllSections(ctx, "b_world");
			ctx.setScreen(() -> null);
		}
		ctx.waitTicks(20);
		SessionRecord s = current(ctx);
		VisitRecord world = s.visits.getLast();
		check(!world.open && VisitRecord.END_LEFT.equals(world.endReason), "world visit closed when leaving: " + world.endReason);
		check(s.disconnects.stream().noneMatch(d -> world.id.equals(d.visitId)), "leaving a world normally is not a disconnect");
		check(world.durationMs > 3000 && world.durationMs < s.runtimeMs, "world duration measured: " + world.durationMs + " ms");
	}

	private void multiplayer(ClientGameTestContext ctx) {
		Properties a = new Properties();
		a.setProperty("server-port", "25601");
		a.setProperty("level-name", "world_a");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(a)) {
			server.connect();
			waitForVisit(ctx);
			VisitRecord v = on(ctx, c -> siq().tracker.visit());
			check(v.isMultiplayer() && "mp:localhost:25601".equals(v.serverKey), "multiplayer visit identity: " + v.serverKey);
			server.runCommand("say Hello from server A");
			server.runCommand("tellraw @a {\"text\":\"Server notice\",\"color\":\"aqua\"}");
			ctx.waitTicks(40);
			disconnectViaPauseMenu(ctx);
			DisconnectRecord intentional = lastDisconnect(ctx);
			check(intentional != null && DisconnectCategory.INTENTIONAL.name().equals(intentional.category)
				&& DisconnectRecord.Certainty.CONFIRMED.name().equals(intentional.certainty), "pause-menu disconnect is intentional (confirmed)");
			check(current(ctx).visits.getLast().chatCount >= 2, "chat counted on the server visit");

			ctx.setScreen(TitleScreen::new);
			server.connect();
			waitForVisit(ctx);
			ctx.waitTicks(20);
			server.runCommand("kick @a Testing kick");
			ctx.waitForScreen(DisconnectedScreen.class);
			DisconnectRecord kick = lastDisconnect(ctx);
			check(kick != null && DisconnectCategory.KICKED.name().equals(kick.category) && kick.reason != null && kick.reason.contains("Testing kick"),
				"kick classified as a server kick with its reason: " + (kick == null ? null : kick.reason));

			ctx.setScreen(TitleScreen::new);
			server.connect();
			waitForVisit(ctx);
			ctx.waitTicks(10);
			SessionRecord s = current(ctx);
			DisconnectRecord after = s.disconnect(kick.id);
			check(after.nextConnectionAt != null && Boolean.TRUE.equals(after.returnedToSameServer) && after.timeToReconnectMs != null,
				"reconnection after the kick recorded (" + after.timeToReconnectMs + " ms)");
			ctx.waitTicks(40);
		}
		ctx.waitForScreen(DisconnectedScreen.class);
		DisconnectRecord closed = lastDisconnect(ctx);
		boolean notice = closed != null && DisconnectCategory.SERVER_CLOSED.name().equals(closed.category)
			&& DisconnectRecord.Certainty.CONFIRMED.name().equals(closed.certainty);
		boolean inferred = closed != null && DisconnectCategory.CONNECTION_LOST.name().equals(closed.category)
			&& DisconnectRecord.Certainty.INFERRED.name().equals(closed.certainty);
		check(notice || inferred, "server stop classified honestly: " + (closed == null ? null : closed.category + "/" + closed.certainty + " shown as \"" + closed.reason + "\""));
		ctx.setScreen(TitleScreen::new);

		Properties b = new Properties();
		b.setProperty("server-port", "25602");
		b.setProperty("level-name", "world_b");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(b)) {
			server.connect();
			waitForVisit(ctx);
			ctx.waitTicks(60);
			disconnectViaPauseMenu(ctx);
		}
		ctx.setScreen(TitleScreen::new);
		Properties a2 = new Properties();
		a2.setProperty("server-port", "25601");
		a2.setProperty("level-name", "world_a2");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(a2)) {
			server.connect();
			waitForVisit(ctx);
			ctx.waitTicks(40);
			disconnectViaPauseMenu(ctx);
		}
		ctx.setScreen(TitleScreen::new);

		SessionRecord s = current(ctx);
		List<ServerAggregate> aggs = ServerAggregate.compute(List.of(s), false, null, ZoneId.systemDefault());
		ServerAggregate serverA = aggs.stream().filter(x -> x.key.equals("mp:localhost:25601")).findFirst().orElseThrow();
		ServerAggregate serverB = aggs.stream().filter(x -> x.key.equals("mp:localhost:25602")).findFirst().orElseThrow();
		check(serverA.visits == 4 && serverB.visits == 1, "A, B, A again produce separate visits (A=" + serverA.visits + ", B=" + serverB.visits + ")");
		long sumA = s.visits.stream().filter(x -> x.serverKey.equals(serverA.key)).mapToLong(x -> x.durationMs).sum();
		check(sumA == serverA.totalMs && serverA.totalMs > 0, "cumulative duration equals the sum of visits");
		check(s.visits.stream().map(x -> x.id).distinct().count() == s.visits.size(), "visit ids are unique");
		check(s.multiplayerMs + s.singleplayerMs == s.inWorldMs && s.inWorldMs <= s.runtimeMs, "playtime never exceeds application time");
	}

	private void failedConnection(ClientGameTestContext ctx) {
		ctx.runOnClient(c -> ConnectScreen.connect(new TitleScreen(), c, ServerAddress.parse("127.0.0.1:25699"),
			new ServerInfo("Offline test server", "127.0.0.1:25699", ServerInfo.ServerType.OTHER), false, null));
		ctx.waitForScreen(DisconnectedScreen.class);
		DisconnectRecord failed = lastDisconnect(ctx);
		check(failed != null && failed.visitId == null && DisconnectCategory.CONNECTION_FAILED.name().equals(failed.category)
			&& "mp:127.0.0.1:25699".equals(failed.serverKey), "failed connection attempt recorded: " + (failed == null ? null : failed.reason));
		ctx.setScreen(TitleScreen::new);
	}

	private void verifyPersistence(ClientGameTestContext ctx, String sessionId) {
		ctx.runOnClient(c -> siq().tracker.checkpoint());
		await(on(ctx, c -> siq().storage.submitIo(() -> null)));
		SessionRecord live = current(ctx);
		SessionRecord onDisk = com.sessioniq.util.Json.read(readUtf8(siq().paths.sessionFile(sessionId, live.startedAt)), SessionRecord.class);
		check(onDisk.visits.size() == live.visits.size() && onDisk.disconnects.size() == live.disconnects.size(), "session file matches memory");
		check(live.memoryUsedMb != null && live.memoryUsedMb.count > 0 && live.memoryMaxMb != null, "memory sampled (" + live.memoryUsedMb.count + " samples)");
		check(Files.exists(siq().paths.samplesFile(sessionId)), "samples file written");
		try (Stream<Path> walk = Files.walk(siq().paths.root)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				String name = p.getFileName().toString();
				if (name.endsWith(".zip")) {
					try (ZipFile zip = new ZipFile(p.toFile())) {
						for (var e : java.util.Collections.list(zip.entries())) {
							check(!new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).contains(SECRET), "no secret in " + name + "/" + e.getName());
						}
					}
					continue;
				}
				String text = readUtf8(p);
				check(!text.contains(SECRET), "no command argument in " + siq().paths.root.relativize(p));
				if (name.endsWith(".json")) {
					JsonParser.parseString(text);
				} else if (name.endsWith(".jsonl")) {
					for (String line : text.split("\n")) {
						if (!line.isBlank()) {
							JsonParser.parseString(line);
						}
					}
				}
			}
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		log("all stored files are valid JSON and contain no command arguments");

		List<SessionRecord> snap = on(ctx, c -> siq().sessions.snapshot());
		StatsEngine.Stats st = StatsEngine.compute(snap, DateRange.of(DateRange.Preset.TODAY, ZoneId.systemDefault()), ZoneId.systemDefault(), false);
		int expectedVisits = production ? 0 : 5;
		check(st.multiplayerVisits >= expectedVisits && st.unexpectedDisconnects >= (production ? 1 : 2) && st.playtimeMs > 0,
			"statistics derive from records (visits=" + st.multiplayerVisits + ", unexpected=" + st.unexpectedDisconnects + ")");

		ChatSearch.Criteria c = new ChatSearch.Criteria();
		c.text = production ? "hello from test" : "server notice";
		ChatSearch search = new ChatSearch(siq().chat, snap, c);
		check(search.next(50).items().size() == 1, "archive search finds the message");
	}

	private void exportsAndBackups(ClientGameTestContext ctx) {
		Path json = await(on(ctx, c -> siq().exports.sessions(siq().sessions.snapshot(), Format.JSON)));
		check(JsonParser.parseString(readUtf8(json)).getAsJsonObject().getAsJsonArray("sessions").size() >= 1, "sessions JSON export");
		Path csv = await(on(ctx, c -> siq().exports.sessions(siq().sessions.snapshot(), Format.CSV)));
		check(readUtf8(csv).lines().count() >= 2, "sessions CSV export");
		Path chatTxt = await(on(ctx, c -> siq().exports.chat(siq().sessions.snapshot(), new ChatSearch.Criteria(), Format.TEXT, k -> k)));
		check(readUtf8(chatTxt).contains("hello from test"), "chat transcript export");
		Path dcs = await(on(ctx, c -> siq().exports.disconnects(siq().sessions.snapshot(), Format.CSV, k -> k)));
		check(readUtf8(dcs).contains(production ? "CONNECTION_FAILED" : "KICKED"), "disconnect CSV export");
		Path servers = await(on(ctx, c -> siq().exports.servers(siq().sessions.snapshot(), Format.JSON, k -> k, k -> null)));
		check(readUtf8(servers).contains(production ? "mp:127.0.0.1:25699" : "mp:localhost:25601"), "server history export");
		StatsEngine.Stats st = on(ctx, c -> StatsEngine.compute(siq().sessions.snapshot(), DateRange.of(DateRange.Preset.ALL, ZoneId.systemDefault()),
			ZoneId.systemDefault(), false));
		Path stats = await(on(ctx, c -> siq().exports.statistics(st, Format.JSON, ms -> ms + "ms", k -> k)));
		check(JsonParser.parseString(readUtf8(stats)).getAsJsonObject().has("summary"), "statistics export");

		Path backup = await(on(ctx, c -> {
			siq().chat.flush();
			return siq().storage.submitIo(() -> siq().backups.createNow("gametest", false));
		}));
		BackupService.VerifyResult vr = siq().backups.verify(backup);
		check(vr.ok() && vr.sessionCount() >= 1, "backup created and verified (" + vr.fileCount() + " files)");

		int before = on(ctx, c -> siq().sessions.size());
		BackupService.RestoreResult merged = await(on(ctx, c -> siq().restore(backup, BackupService.RestoreMode.MERGE)));
		check(merged.sessionsRestored() == 0 && on(ctx, c -> siq().sessions.size()) == before, "merge restore adds nothing that already exists");
		String activeId = on(ctx, c -> siq().tracker.session().id);
		BackupService.RestoreResult replaced = await(on(ctx, c -> siq().restore(backup, BackupService.RestoreMode.REPLACE)));
		check(on(ctx, c -> siq().sessions.get(activeId) != null && siq().tracker.session().id.equals(activeId)), "replace restore keeps the recording session");
		check(replaced.safetyBackup() != null && Files.exists(siq().paths.backups.resolve(replaced.safetyBackup())), "a safety backup is made before restoring");
	}

	private void guiInteraction(ClientGameTestContext ctx, String sessionId) {
		ctx.setScreen(() -> null);
		ctx.waitTicks(5);
		ctx.setScreen(TitleScreen::new);
		ctx.waitTicks(5);
		ctx.setScreen(() -> new SiqScreen(null));
		ctx.waitForScreen(SiqScreen.class);
		ctx.waitTicks(5);

		double[] pos = on(ctx, c -> {
			double s = ((SiqScreen) c.currentScreen).ui.phys;
			int itemH = siq().config.get().compact ? 20 : 24;
			boolean collapsed = ((SiqScreen) c.currentScreen).ui.width < 560;
			double vx = collapsed ? 17 : 60;
			double vy = 40 + (itemH + 2) + itemH / 2.0;
			return new double[]{vx * s, vy * s};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTicks(3);
		ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTicks(10);
		log(on(ctx, c -> {
			SiqScreen sc = (SiqScreen) c.currentScreen;
			return "click debug: target=" + pos[0] + "," + pos[1] + " mouse=" + c.mouse.getX() + "," + c.mouse.getY() + " window=" + c.getWindow().getWidth() + "x"
				+ c.getWindow().getHeight() + " fb=" + c.getWindow().getFramebufferWidth() + "x" + c.getWindow().getFramebufferHeight() + " gui=" + c.getWindow().getScaleFactor()
				+ " ui.phys=" + sc.ui.phys + " ui.scale=" + sc.ui.scale + " ui.mx=" + sc.ui.mx + "," + sc.ui.my + " page=" + sc.current().getClass().getSimpleName();
		}));
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).current() instanceof SessionsPage), "clicking the sidebar navigates");
		ctx.takeScreenshot("c_sessions_clicked");

		SessionDetailPage detail = on(ctx, c -> {
			SessionDetailPage p = new SessionDetailPage((SiqScreen) c.currentScreen, sessionId);
			((SiqScreen) c.currentScreen).push(p);
			return p;
		});
		for (int tab = 0; tab < 6; tab++) {
			setTab(ctx, detail, "tab", tab);
			ctx.waitTicks(30);
			ctx.takeScreenshot("d_session_tab" + tab);
		}
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(5);
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).current() instanceof SessionsPage), "Escape returns to the previous page");

		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).push(new ServerDetailPage((SiqScreen) c.currentScreen, "mp:localhost:25601")));
		ctx.waitTicks(30);
		ctx.takeScreenshot("e_server_detail");

		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("settings"));
		Object settings = on(ctx, c -> ((SiqScreen) c.currentScreen).current());
		for (int tab = 1; tab < 8; tab++) {
			setTab(ctx, settings, "tab", tab);
			ctx.waitTicks(30);
			ctx.takeScreenshot("e_settings_tab" + tab);
		}
		setTab(ctx, settings, "tab", 4);
		ctx.runOnClient(c -> {
			try {
				Field editing = settings.getClass().getDeclaredField("editing");
				Field source = settings.getClass().getDeclaredField("editingSource");
				editing.setAccessible(true);
				source.setAccessible(true);
				Theme base = siq().themes.active();
				Theme copy = base.copy("Obsidian Custom");
				source.set(settings, base);
				editing.set(settings, copy);
				siq().themes.preview(copy);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		ctx.waitTicks(20);
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.scrollTo("set#body4", 160));
		ctx.waitTicks(30);
		ctx.takeScreenshot("e_theme_editor");
		ctx.runOnClient(c -> {
			try {
				Field editing = settings.getClass().getDeclaredField("editing");
				editing.setAccessible(true);
				editing.set(settings, null);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			siq().themes.select("Obsidian");
			SiqScreen sc = (SiqScreen) c.currentScreen;
			sc.openModal(new com.sessioniq.gui.ExportModal(sc, List.of(
				new com.sessioniq.gui.ExportModal.Option("All sessions", "Every session with visits, disconnects and summaries.", List.of(Format.JSON, Format.CSV),
					f -> CompletableFuture.completedFuture(null)))));
		});
		ctx.waitTicks(30);
		ctx.takeScreenshot("e_export_modal");
		ctx.runOnClient(c -> {
			SiqScreen sc = (SiqScreen) c.currentScreen;
			sc.ui.closePopup();
			sc.openModal(new com.sessioniq.gui.ConfirmModal("Delete this session?", "This permanently deletes the session and everything recorded in it.",
				"Delete session", true, () -> { }));
		});
		ctx.waitTicks(30);
		ctx.takeScreenshot("e_confirm_modal");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(5);
		check(on(ctx, c -> !((SiqScreen) c.currentScreen).hasModal()), "Escape closes dialogs");

		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("chat"));
		ctx.waitTicks(10);
		ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).ui.focus("chat#q"));
		ctx.getInput().typeChars("hello");
		ctx.waitTicks(40);
		ctx.takeScreenshot("f_chat_search");
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).ui.field("chat#q").text.equals("hello")), "typing goes into the focused search field");
		check(on(ctx, c -> ((SiqScreen) c.currentScreen).current() instanceof ChatPage), "chat page shown");

		int[][] sizes = {{640, 400}, {1280, 720}};
		for (int[] size : sizes) {
			ctx.getInput().resizeWindow(size[0], size[1]);
			ctx.waitTicks(10);
			for (String section : List.of("overview", "statistics", "settings")) {
				ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection(section));
				ctx.waitTicks(25);
				ctx.takeScreenshot("g_" + size[0] + "x" + size[1] + "_" + section);
			}
		}
		for (int scale : new int[]{1, 4}) {
			ctx.runOnClient(c -> {
				c.options.getGuiScale().setValue(scale);
				c.onResolutionChanged();
			});
			ctx.waitTicks(10);
			ctx.runOnClient(c -> ((SiqScreen) c.currentScreen).selectSection("overview"));
			ctx.waitTicks(20);
			ctx.takeScreenshot("h_guiscale" + scale);
		}
		ctx.runOnClient(c -> {
			c.options.getGuiScale().setValue(0);
			c.onResolutionChanged();
		});

		for (String theme : List.of("Light", "Violet", "Emerald", "Bubblegum", "Light Purple", "Baby Blue")) {
			ctx.runOnClient(c -> {
				siq().themes.select(theme);
				siq().config.get().theme = theme;
				siq().config.changed();
				((SiqScreen) c.currentScreen).selectSection("statistics");
			});
			ctx.waitTicks(25);
			ctx.takeScreenshot("i_theme_" + theme);
		}
		String saveError = on(ctx, c -> {
			Theme custom = siq().themes.find("Midnight Blue").copy("Gametest Theme");
			custom.accent = 0xFF7A00;
			return siq().themes.saveCustom(custom);
		});
		check(saveError == null && readUtf8(siq().paths.themes).contains("Gametest Theme"), "custom theme saved to themes.json");
		check(on(ctx, c -> siq().themes.deleteCustom("Obsidian")) != null, "built-in themes cannot be deleted");
		check(on(ctx, c -> siq().themes.deleteCustom("Gametest Theme")) == null, "custom theme deleted");
		ctx.runOnClient(c -> {
			siq().themes.select("Obsidian");
			siq().config.get().theme = "Obsidian";
			siq().config.changed();
		});
		ctx.setScreen(TitleScreen::new);
	}

	private static String readUtf8(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new AssertionError("could not read " + p, e);
		}
	}

	private Path marker() {
		return siq().paths.root.resolveSibling("crash-marker.txt");
	}

	private void crashPhase(ClientGameTestContext ctx) {
		List<SessionRecord> history = on(ctx, c -> siq().sessions.snapshot());
		SessionRecord previous = history.stream().filter(s -> !s.isActive()).findFirst().orElse(null);
		check(previous != null && SessionRecord.STATUS_COMPLETED.equals(previous.status) && !previous.recovered,
			"previous session was completed by the normal shutdown");
		check(previous.visits.size() >= 6 && previous.disconnects.size() >= 4, "previous session's visits and disconnects persisted across restart");

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			waitForVisit(ctx);
			sendChat(ctx, "message before the crash");
			ctx.waitTicks(40);
			ctx.runOnClient(c -> siq().tracker.checkpoint());
			await(on(ctx, c -> siq().storage.submitIo(() -> null)));
			SessionRecord live = current(ctx);
			SessionRecord s = com.sessioniq.util.Json.read(readUtf8(siq().paths.sessionFile(live.id, live.startedAt)), SessionRecord.class);
			check(s.isActive() && s.visits.getLast().open, "active state persisted before the crash");
			try {
				Files.writeString(marker(), s.id + "\n" + s.runtimeMs + "\n" + s.visits.getLast().id);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			log("halting the JVM without shutdown while in a world (session " + s.id + ")");
			Runtime.getRuntime().halt(3);
		}
	}

	private void recoverPhase(ClientGameTestContext ctx) {
		List<String> lines;
		try {
			lines = Files.readAllLines(marker());
		} catch (java.io.IOException e) {
			throw new AssertionError("run the crash phase first", e);
		}
		String id = lines.get(0);
		long runtimeAtCheckpoint = Long.parseLong(lines.get(1));
		String visitId = lines.get(2);
		SessionRecord s = on(ctx, c -> siq().sessions.get(id));
		check(s != null, "interrupted session still exists");
		check(SessionRecord.STATUS_INTERRUPTED.equals(s.status) && s.recovered && s.recoveryNote != null, "interrupted session finalized as recovered");
		VisitRecord v = s.visit(visitId);
		check(v != null && !v.open && v.interrupted, "open visit closed as interrupted");
		check(s.runtimeMs >= runtimeAtCheckpoint && s.runtimeMs <= runtimeAtCheckpoint + 5 * 60_000, "duration kept, not duplicated (" + s.runtimeMs + " ms)");
		DisconnectRecord d = s.disconnect(v.disconnectId);
		check(d != null && DisconnectCategory.ABNORMAL_TERMINATION.name().equals(d.category), "abnormal termination recorded");
		check(!DisconnectRecord.Certainty.CONFIRMED.name().equals(d.certainty), "cause not presented as confirmed (" + d.certainty + ")");
		check(on(ctx, c -> siq().recovery().recoveredIds().contains(id)), "recovery reported at startup");
		check(chatOf(ctx, id).stream().anyMatch(r -> r.text.contains("message before the crash")), "chat written before the crash survived");
		ChatSearch.Criteria c = new ChatSearch.Criteria();
		c.text = "hello from test";
		check(new ChatSearch(siq().chat, on(ctx, x -> siq().sessions.snapshot()), c).next(10).items().size() >= 1, "chat from earlier runs still searchable");
		check(on(ctx, x -> siq().tracker.session()) != null && !id.equals(on(ctx, x -> siq().tracker.session().id)), "a new session is recording");
		ctx.setScreen(() -> new SiqScreen(null));
		ctx.waitForScreen(SiqScreen.class);
		ctx.waitTicks(30);
		ctx.takeScreenshot("j_recovered_overview");
		ctx.runOnClient(x -> ((SiqScreen) x.currentScreen).push(new SessionDetailPage((SiqScreen) x.currentScreen, id)));
		ctx.waitTicks(30);
		ctx.takeScreenshot("j_recovered_session");
		ctx.setScreen(TitleScreen::new);
		deletions(ctx, id);
		log("recover phase complete");
	}

	private void deletions(ClientGameTestContext ctx, String recoveredId) {
		SessionRecord full = on(ctx, c -> siq().sessions.sorted().stream()
			.filter(s -> s.visits.stream().anyMatch(v -> v.serverKey.equals("mp:localhost:25602"))).findFirst().orElse(null));
		check(full != null, "the full-run session is available for deletion tests");
		String fullId = full.id;

		ChatRecord victim = chatOf(ctx, fullId).getFirst();
		int chatBefore = full.chatCount;
		int removed = await(on(ctx, c -> com.sessioniq.storage.DataActions.deleteChat(siq(), List.of(victim))));
		ctx.waitTicks(5);
		SessionRecord afterChat = on(ctx, c -> siq().sessions.get(fullId));
		check(removed == 1 && afterChat.chatCount == chatBefore - 1 && chatOf(ctx, fullId).stream().noneMatch(r -> r.id.equals(victim.id)),
			"deleting a message updates the archive and counters");

		DisconnectRecord kick = afterChat.disconnects.stream().filter(d -> DisconnectCategory.KICKED.name().equals(d.category)).findFirst().orElseThrow();
		ctx.runOnClient(c -> com.sessioniq.storage.DataActions.deleteDisconnect(siq(), fullId, kick.id));
		SessionRecord afterDc = on(ctx, c -> siq().sessions.get(fullId));
		check(afterDc.disconnect(kick.id) == null && afterDc.visits.stream().noneMatch(v -> kick.id.equals(v.disconnectId)), "deleting a disconnect clears references");

		String msg = await(on(ctx, c -> com.sessioniq.storage.DataActions.deleteServerHistory(siq(), "mp:localhost:25602")));
		ctx.waitTicks(5);
		SessionRecord afterServer = on(ctx, c -> siq().sessions.get(fullId));
		check(afterServer.visits.stream().noneMatch(v -> v.serverKey.equals("mp:localhost:25602"))
			&& afterServer.multiplayerMs == afterServer.visits.stream().filter(VisitRecord::isMultiplayer).mapToLong(v -> v.durationMs).sum(),
			"deleting a server's history removes its visits and updates totals (" + msg + ")");
		List<ServerAggregate> aggs = on(ctx, c -> ServerAggregate.compute(siq().sessions.snapshot(), false, null, ZoneId.systemDefault()));
		check(aggs.stream().noneMatch(a -> a.key.equals("mp:localhost:25602")), "statistics no longer include the deleted server");

		ctx.runOnClient(c -> com.sessioniq.storage.DataActions.deleteSession(siq(), recoveredId));
		await(on(ctx, c -> siq().storage.submitIo(() -> null)));
		check(on(ctx, c -> siq().sessions.get(recoveredId)) == null && !Files.exists(siq().paths.chatDir(recoveredId))
			&& !Files.exists(siq().paths.samplesFile(recoveredId)), "deleting a session removes its files");

		await(on(ctx, c -> com.sessioniq.storage.DataActions.clearAllChat(siq())));
		ctx.waitTicks(5);
		check(on(ctx, c -> siq().sessions.sorted().stream().allMatch(s -> s.isActive() || s.chatCount == 0)) && !Files.exists(siq().paths.chat),
			"clearing chat removes the archive and zeroes counters");
		String activeBefore = on(ctx, c -> siq().tracker.session().id);
		await(on(ctx, c -> com.sessioniq.storage.DataActions.clearAllHistory(siq())));
		ctx.waitTicks(10);
		await(on(ctx, c -> siq().storage.submitIo(() -> null)));
		check(on(ctx, c -> siq().sessions.size()) == 1 && !on(ctx, c -> siq().tracker.session().id).equals(activeBefore),
			"clearing all history leaves only a fresh session");
		check(Files.exists(siq().paths.config) && Files.isDirectory(siq().paths.backups), "settings and backups are kept");
	}
}
