package com.sessioniq;

import com.sessioniq.gui.SiqScreen;
import com.sessioniq.mixin.ConnectScreenAccessor;
import com.sessioniq.mixin.DeathScreenAccessor;
import com.sessioniq.track.HookCheck;
import com.sessioniq.util.Safe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class SessionIQClient implements ClientModInitializer {
	public static KeyBinding openKey;
	private static final Set<Screen> SEEN_SCREENS = Collections.newSetFromMap(new WeakHashMap<>());

	@Override
	public void onInitializeClient() {
		SessionIQ siq = SessionIQ.init();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(SessionIQ.MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.sessioniq.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Safe.run("tick", () -> siq.tick(client));
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new SiqScreen(null));
				}
			}
			Safe.run("online notice", () -> showNoticeOnTitleScreen(siq, client));
		});
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> HookCheck.missing());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Safe.run("shutdown", siq::shutdown));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			client.execute(() -> Safe.run("join", () -> siq.tracker.onJoin(handler, client))));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientConnection connection = handler.getConnection();
			client.execute(() -> Safe.run("disconnect", () -> siq.tracker.onConnectionEnded(connection)));
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
			Safe.run("chat capture", () -> siq.chatCapture.onChatMessage(message, sender, params)));
		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
			Safe.run("chat capture", () -> siq.chatCapture.onGameMessage(message, overlay)));
		ClientSendMessageEvents.COMMAND.register(command -> Safe.run("command echo guard", () -> siq.chatCapture.onCommandSent(command)));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> Safe.run("screen hook", () -> onScreen(siq, client, screen)));
	}

	/** First launch with online features: open the dashboard on the title screen with the notice on top. */
	private static void showNoticeOnTitleScreen(SessionIQ siq, MinecraftClient client) {
		if (client.currentScreen instanceof TitleScreen title && siq.isReady() && siq.online.noticeDue()) {
			if (++titleTicks > 30) {
				client.setScreen(new SiqScreen(title));
			}
		} else {
			titleTicks = 0;
		}
	}

	private static int titleTicks;

	private static void onScreen(SessionIQ siq, MinecraftClient client, Screen screen) {
		if (!SEEN_SCREENS.add(screen)) {
			return;
		}
		if (screen instanceof DeathScreen death) {
			Text message = ((DeathScreenAccessor) death).sessioniq$message();
			siq.tracker.recordDeath(message == null ? null : message.getString());
		} else if (screen instanceof ConnectScreen connect) {
			ScreenEvents.remove(connect).register(s ->
				Safe.run("connect screen", () -> siq.tracker.onConnectScreenClosed(((ConnectScreenAccessor) connect).sessioniq$isCancelled())));
		}
	}
}
