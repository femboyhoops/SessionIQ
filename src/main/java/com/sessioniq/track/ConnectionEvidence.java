package com.sessioniq.track;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ConnectionEvidence {
	private static final Map<ClientConnection, Entry> ENTRIES = Collections.synchronizedMap(new WeakHashMap<>());

	private ConnectionEvidence() {
	}

	public static final class Entry {
		volatile boolean serverPacket;
		volatile String serverPacketPhase;
		volatile Text serverReason;
		volatile Text firstReason;
		volatile DisconnectionInfo listenerInfo;
		volatile String listenerPhase;
		volatile boolean clientShutdown;
	}

	private static Entry entry(ClientConnection connection) {
		synchronized (ENTRIES) {
			return ENTRIES.computeIfAbsent(connection, c -> new Entry());
		}
	}

	public static Entry peek(ClientConnection connection) {
		return connection == null ? null : ENTRIES.get(connection);
	}

	public static void serverDisconnectPacket(ClientConnection connection, String phase, Text reason) {
		Entry e = entry(connection);
		e.serverPacket = true;
		e.serverPacketPhase = phase;
		e.serverReason = reason;
	}

	public static void disconnectCalled(ClientConnection connection, DisconnectionInfo info) {
		Entry e = entry(connection);
		synchronized (e) {
			if (e.firstReason == null && info != null) {
				e.firstReason = info.reason();
			}
		}
	}

	public static void listenerDisconnected(ClientConnection connection, DisconnectionInfo info, String phase) {
		Entry e = entry(connection);
		e.listenerInfo = info;
		e.listenerPhase = phase;
	}

	public static void markClientShutdown(ClientConnection connection) {
		if (connection != null) {
			entry(connection).clientShutdown = true;
		}
	}

	public static boolean hasReason(ClientConnection connection) {
		Entry e = peek(connection);
		return e != null && (e.firstReason != null || e.listenerInfo != null);
	}

	public static DisconnectClassifier.Facts facts(ClientConnection connection, String defaultPhase) {
		DisconnectClassifier.Facts f = new DisconnectClassifier.Facts();
		f.phase = defaultPhase;
		Entry e = peek(connection);
		Text reason = null;
		if (e != null) {
			f.clientShutdown = e.clientShutdown;
			f.serverPacket = e.serverPacket;
			if (e.listenerPhase != null) {
				f.phase = e.listenerPhase;
			} else if (e.serverPacketPhase != null) {
				f.phase = e.serverPacketPhase;
			}
			reason = e.serverPacket && e.serverReason != null ? e.serverReason : e.firstReason;
			if (e.serverPacket && e.serverReason != null && e.firstReason != null && e.firstReason != e.serverReason) {
				f.firstSignalText = Sanitize.oneLine(e.firstReason.getString(), 600);
			}
			if (e.listenerInfo != null) {
				if (reason == null) {
					reason = e.listenerInfo.reason();
				}
				f.displayedText = Sanitize.oneLine(e.listenerInfo.reason().getString(), 600);
				e.listenerInfo.report().ifPresent(p -> f.reportFile = p.getFileName().toString());
			}
		}
		if (connection != null && connection.getDisconnectionInfo() != null) {
			if (reason == null) {
				reason = connection.getDisconnectionInfo().reason();
			}
			if (f.displayedText == null) {
				f.displayedText = Sanitize.oneLine(connection.getDisconnectionInfo().reason().getString(), 600);
			}
		}
		describe(reason, f);
		return f;
	}

	public static void describe(Text reason, DisconnectClassifier.Facts f) {
		if (reason == null) {
			return;
		}
		f.reasonText = Sanitize.oneLine(reason.getString(), 600);
		if (reason.getContent() instanceof TranslatableTextContent translatable) {
			f.reasonKey = translatable.getKey();
			Object[] args = translatable.getArgs();
			if (args.length > 0) {
				Object a = args[0];
				f.reasonArg = a instanceof Text t ? t.getString() : String.valueOf(a);
			}
		}
	}
}
