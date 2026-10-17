package com.sessioniq.track;

import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord.Certainty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DisconnectClassifier {
	private DisconnectClassifier() {
	}

	public static final class Facts {
		public boolean clientShutdown;
		public boolean serverPacket;
		public String phase = "unknown";
		public String reasonKey;
		public String reasonText;
		public String reasonArg;
		public String reportFile;
		public String displayedText;
		public String firstSignalText;
		public boolean userCancelled;
	}

	public record Result(DisconnectCategory category, Certainty certainty, List<String> evidence, String details) {
	}

	public static Result classify(Facts f) {
		List<String> evidence = new ArrayList<>();
		String key = f.reasonKey == null ? "" : f.reasonKey;
		String arg = f.reasonArg == null ? "" : f.reasonArg;
		boolean attempt = "connect".equals(f.phase) || "login".equals(f.phase);

		if (f.userCancelled || "connect.aborted".equals(key)) {
			evidence.add("The connection attempt was cancelled from the connecting screen");
			return new Result(DisconnectCategory.CONNECTION_ABORTED, Certainty.CONFIRMED, evidence, null);
		}
		if (f.clientShutdown) {
			evidence.add("Minecraft was closed normally while connected");
			return new Result(DisconnectCategory.INTENTIONAL, Certainty.CONFIRMED, evidence, null);
		}
		if (!f.serverPacket && "multiplayer.status.quitting".equals(key)) {
			evidence.add("The client disconnected at the player's request (Disconnect / Save and Quit)");
			return new Result(DisconnectCategory.INTENTIONAL, Certainty.CONFIRMED, evidence, null);
		}
		if (!f.serverPacket && "multiplayer.requiredTexturePrompt.disconnect".equals(key)) {
			evidence.add("The player declined a resource pack the server requires");
			return new Result(DisconnectCategory.INTENTIONAL, Certainty.CONFIRMED, evidence, null);
		}
		if ("disconnect.transfer".equals(key)) {
			evidence.add("The server transferred the client to another server");
			return new Result(DisconnectCategory.TRANSFER, Certainty.CONFIRMED, evidence, null);
		}
		if (f.serverPacket) {
			evidence.add("The server sent an explicit disconnect message");
			if (attempt) {
				evidence.add("The server refused the login");
				return new Result(DisconnectCategory.CONNECTION_FAILED, Certainty.CONFIRMED, evidence, null);
			}
			if ("multiplayer.disconnect.server_shutdown".equals(key)) {
				evidence.add("The message reports that the server is shutting down");
				return new Result(DisconnectCategory.SERVER_CLOSED, Certainty.CONFIRMED, evidence, null);
			}
			return new Result(DisconnectCategory.KICKED, Certainty.CONFIRMED, evidence, null);
		}
		if ("disconnect.timeout".equals(key)) {
			evidence.add("The client's read timeout expired: no data arrived from the server in time");
			return new Result(attempt ? DisconnectCategory.CONNECTION_FAILED : DisconnectCategory.TIMEOUT, Certainty.CONFIRMED, evidence, null);
		}
		if ("disconnect.packetError".equals(key)) {
			evidence.add("The client failed to process a packet from the server");
			if (f.reportFile != null) {
				evidence.add("A diagnostic report was written: " + f.reportFile);
			}
			return new Result(DisconnectCategory.PROTOCOL_ERROR, Certainty.CONFIRMED, evidence, null);
		}
		if ("disconnect.genericReason".equals(key) && !arg.isEmpty()) {
			String details = Sanitize.text(arg, 600);
			if (attempt) {
				evidence.add("The connection could not be established");
				return new Result(DisconnectCategory.CONNECTION_FAILED, Certainty.CONFIRMED, evidence, details);
			}
			if (arg.contains("Internal Exception")) {
				evidence.add("The network layer reported an exception");
				if (looksLikeNetworkException(arg)) {
					return new Result(DisconnectCategory.NETWORK_FAILURE, Certainty.CONFIRMED, evidence, details);
				}
				return new Result(DisconnectCategory.NETWORK_FAILURE, Certainty.INFERRED, evidence, details);
			}
			evidence.add("The client reported a generic disconnect reason");
			return new Result(DisconnectCategory.UNKNOWN, Certainty.UNKNOWN, evidence, details);
		}
		if ("disconnect.endOfStream".equals(key)) {
			evidence.add("The connection was closed from the remote side without a disconnect message");
			return new Result(attempt ? DisconnectCategory.CONNECTION_FAILED : DisconnectCategory.CONNECTION_LOST, Certainty.INFERRED, evidence, null);
		}
		if (attempt) {
			evidence.add("The connection attempt did not complete");
			return new Result(DisconnectCategory.CONNECTION_FAILED, key.isEmpty() && f.reasonText == null ? Certainty.UNKNOWN : Certainty.CONFIRMED, evidence, null);
		}
		if (key.isEmpty() && f.reasonText == null) {
			evidence.add("No disconnect reason was available");
		} else if ("multiplayer.disconnect.generic".equals(key)) {
			evidence.add("The client only reported a generic \"Disconnected\" reason");
		} else {
			evidence.add("The disconnect reason did not identify a cause");
		}
		return new Result(DisconnectCategory.UNKNOWN, Certainty.UNKNOWN, evidence, null);
	}

	private static boolean looksLikeNetworkException(String s) {
		String l = s.toLowerCase(Locale.ROOT);
		return l.contains("socketexception") || l.contains("ioexception") || l.contains("connection reset") || l.contains("connection refused")
			|| l.contains("broken pipe") || l.contains("unresolvedaddress") || l.contains("unknownhost") || l.contains("noroutetohost")
			|| l.contains("closedchannel") || l.contains("connectexception") || l.contains("sockettimeout");
	}
}
