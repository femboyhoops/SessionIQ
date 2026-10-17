package com.sessioniq;

import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord.Certainty;
import com.sessioniq.track.DisconnectClassifier;
import com.sessioniq.track.DisconnectClassifier.Facts;
import com.sessioniq.track.Sanitize;
import com.sessioniq.track.ServerIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassifierTest {
	private static Facts facts(String phase, String key, String arg, boolean serverPacket) {
		Facts f = new Facts();
		f.phase = phase;
		f.reasonKey = key;
		f.reasonArg = arg;
		f.reasonText = key;
		f.serverPacket = serverPacket;
		return f;
	}

	@Test
	void intentionalQuitIsConfirmed() {
		var r = DisconnectClassifier.classify(facts("play", "multiplayer.status.quitting", null, false));
		assertEquals(DisconnectCategory.INTENTIONAL, r.category());
		assertEquals(Certainty.CONFIRMED, r.certainty());
	}

	@Test
	void gameShutdownWhileConnectedIsIntentional() {
		Facts f = facts("play", null, null, false);
		f.clientShutdown = true;
		assertEquals(DisconnectCategory.INTENTIONAL, DisconnectClassifier.classify(f).category());
	}

	@Test
	void serverPacketMeansKickOrShutdown() {
		assertEquals(DisconnectCategory.KICKED, DisconnectClassifier.classify(facts("play", "multiplayer.disconnect.kicked", null, true)).category());
		assertEquals(DisconnectCategory.KICKED, DisconnectClassifier.classify(facts("play", null, null, true)).category());
		assertEquals(DisconnectCategory.SERVER_CLOSED, DisconnectClassifier.classify(facts("play", "multiplayer.disconnect.server_shutdown", null, true)).category());
	}

	@Test
	void quittingKeySentByServerIsNotTreatedAsIntentional() {
		assertEquals(DisconnectCategory.KICKED, DisconnectClassifier.classify(facts("play", "multiplayer.status.quitting", null, true)).category());
	}

	@Test
	void timeoutsAndNetworkErrorsAreDistinct() {
		assertEquals(DisconnectCategory.TIMEOUT, DisconnectClassifier.classify(facts("play", "disconnect.timeout", null, false)).category());
		var net = DisconnectClassifier.classify(facts("play", "disconnect.genericReason", "Internal Exception: java.net.SocketException: Connection reset", false));
		assertEquals(DisconnectCategory.NETWORK_FAILURE, net.category());
		assertEquals(Certainty.CONFIRMED, net.certainty());
		var other = DisconnectClassifier.classify(facts("play", "disconnect.genericReason", "Internal Exception: java.lang.IllegalStateException: odd", false));
		assertEquals(DisconnectCategory.NETWORK_FAILURE, other.category());
		assertEquals(Certainty.INFERRED, other.certainty());
	}

	@Test
	void remoteCloseIsOnlyInferred() {
		var r = DisconnectClassifier.classify(facts("play", "disconnect.endOfStream", null, false));
		assertEquals(DisconnectCategory.CONNECTION_LOST, r.category());
		assertEquals(Certainty.INFERRED, r.certainty());
	}

	@Test
	void missingOrGenericReasonStaysUnknown() {
		assertEquals(DisconnectCategory.UNKNOWN, DisconnectClassifier.classify(facts("play", null, null, false)).category());
		var g = DisconnectClassifier.classify(facts("play", "multiplayer.disconnect.generic", null, false));
		assertEquals(DisconnectCategory.UNKNOWN, g.category());
		assertEquals(Certainty.UNKNOWN, g.certainty());
	}

	@Test
	void attemptsThatFailAreFailedConnections() {
		assertEquals(DisconnectCategory.CONNECTION_FAILED,
			DisconnectClassifier.classify(facts("connect", "disconnect.genericReason", "Connection refused: no further information", false)).category());
		assertEquals(DisconnectCategory.CONNECTION_FAILED, DisconnectClassifier.classify(facts("login", "multiplayer.disconnect.not_whitelisted", null, true)).category());
		Facts cancel = facts("connect", null, null, false);
		cancel.userCancelled = true;
		assertEquals(DisconnectCategory.CONNECTION_ABORTED, DisconnectClassifier.classify(cancel).category());
	}

	@Test
	void transferAndProtocolErrors() {
		assertEquals(DisconnectCategory.TRANSFER, DisconnectClassifier.classify(facts("play", "disconnect.transfer", null, false)).category());
		assertEquals(DisconnectCategory.PROTOCOL_ERROR, DisconnectClassifier.classify(facts("play", "disconnect.packetError", null, false)).category());
	}

	@Test
	void sanitizeRemovesFormattingAndControls() {
		assertEquals("Hello world", Sanitize.text("§cHello\u0007 §lworld", 100));
		assertEquals(5 + 1, Sanitize.text("abcdefghij", 5).length());
	}

	@Test
	void serverIdentityNormalizesHostAndKeepsPorts() {
		assertEquals("mp:play.example.com:25565", ServerIdentity.multiplayerKey("Play.Example.COM.", 25565));
		assertEquals("mp:play.example.com:25566", ServerIdentity.multiplayerKey("play.example.com", 25566));
		assertEquals("mp:play.example.com", ServerIdentity.groupKey("mp:play.example.com:25566", true));
		assertEquals("mp:play.example.com:25566", ServerIdentity.groupKey("mp:play.example.com:25566", false));
		assertEquals("sp:New World", ServerIdentity.groupKey("sp:New World", true));
	}
}
