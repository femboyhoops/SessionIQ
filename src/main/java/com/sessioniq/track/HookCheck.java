package com.sessioniq.track;

import com.sessioniq.util.SiqLog;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class HookCheck {
	private static List<String> missing;

	private HookCheck() {
	}

	public static List<String> missing() {
		if (missing == null) {
			missing = run();
		}
		return missing;
	}

	private static List<String> run() {
		List<String> out = new ArrayList<>();
		check(out, ClientConnection.class, "sessioniq$recordDisconnectReason", "disconnect reasons");
		check(out, ClientCommonNetworkHandler.class, "sessioniq$serverSentDisconnect", "server kick detection");
		check(out, ClientCommonNetworkHandler.class, "sessioniq$disconnected", "disconnect details");
		check(out, ClientLoginNetworkHandler.class, "sessioniq$loginDisconnected", "login failures");
		check(out, ConnectScreen.class, "sessioniq$attempt", "connection attempts");
		check(out, DisconnectedScreen.class, "sessioniq$shown", "failed connections");
		for (String m : out) {
			SiqLog.warn("SessionIQ hook not applied: {}", m);
		}
		return out;
	}

	private static void check(List<String> out, Class<?> target, String handler, String feature) {
		try {
			for (Method m : target.getDeclaredMethods()) {
				if (m.getName().contains(handler)) {
					return;
				}
			}
		} catch (Throwable t) {
			out.add(feature + " (could not inspect: " + t.getClass().getSimpleName() + ")");
			return;
		}
		out.add(feature);
	}
}
