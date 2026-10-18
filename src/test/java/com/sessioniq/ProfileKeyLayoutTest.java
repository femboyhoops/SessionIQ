package com.sessioniq;

import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.network.encryption.SignatureVerifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The online service (backend/src/auth.ts, mojangIssued) rebuilds the bytes Mojang signs for a
 * player key as UUID (16 bytes) + expiry in ms (8 bytes, big-endian) + X.509 public key, and checks
 * them with SHA1withRSA. This test signs that same layout and has Minecraft's own verifier accept it,
 * so a change in the game's format would show up here.
 */
class ProfileKeyLayoutTest {
	private static KeyPair rsa() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	private static byte[] serverLayout(UUID uuid, Instant expiresAt, byte[] publicKey) {
		ByteBuffer b = ByteBuffer.allocate(24 + publicKey.length);
		b.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).putLong(expiresAt.toEpochMilli()).put(publicKey);
		return b.array();
	}

	@Test
	void serverPayloadMatchesTheGamesPlayerKeyCheck() throws Exception {
		KeyPair mojang = rsa();
		KeyPair player = rsa();
		UUID uuid = UUID.randomUUID();
		Instant expiresAt = Instant.now().plusSeconds(3600);
		Signature s = Signature.getInstance("SHA1withRSA");
		s.initSign(mojang.getPrivate());
		s.update(serverLayout(uuid, expiresAt, player.getPublic().getEncoded()));
		PlayerPublicKey.PublicKeyData data = new PlayerPublicKey.PublicKeyData(expiresAt, player.getPublic(), s.sign());
		SignatureVerifier verifier = SignatureVerifier.create(mojang.getPublic(), "SHA1withRSA");

		assertDoesNotThrow(() -> PlayerPublicKey.verifyAndDecode(verifier, uuid, data));
		assertThrows(PlayerPublicKey.PublicKeyException.class, () -> PlayerPublicKey.verifyAndDecode(verifier, UUID.randomUUID(), data),
			"a key issued to one account must not verify for another");
	}
}
