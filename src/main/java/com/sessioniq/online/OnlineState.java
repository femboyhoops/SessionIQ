package com.sessioniq.online;

import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;
import com.sessioniq.util.SiqLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * online.json: the anonymous install id and SessionIQ's own sign-in token. The token is issued by
 * the SessionIQ server once the account is proven with its Mojang-signed profile key; no Minecraft login token is ever read or stored.
 */
public final class OnlineState extends Json.Extensible {
	public String installId;
	public String token;
	public long tokenExpiresAt;
	public String uuid;
	public String name;
	public boolean admin;

	public static OnlineState load(Path file) {
		OnlineState s = null;
		if (Files.exists(file)) {
			try {
				s = Json.read(Files.readString(file, StandardCharsets.UTF_8), OnlineState.class);
			} catch (Exception e) {
				SiqLog.warn("SessionIQ could not read online.json ({}); starting fresh", e.toString());
			}
		}
		if (s == null) {
			s = new OnlineState();
		}
		if (s.installId == null || s.installId.isBlank()) {
			s.installId = UUID.randomUUID().toString();
			s.save(file);
		}
		return s;
	}

	public void save(Path file) {
		try {
			AtomicFiles.writeString(file, Json.write(this, true));
		} catch (Exception e) {
			SiqLog.warn("SessionIQ could not save online.json: {}", e.toString());
		}
	}

	public boolean hasToken(String forUuid) {
		return token != null && forUuid != null && forUuid.equals(uuid) && tokenExpiresAt > System.currentTimeMillis() + 60_000;
	}

	public void clearToken() {
		token = null;
		tokenExpiresAt = 0;
		admin = false;
	}
}
