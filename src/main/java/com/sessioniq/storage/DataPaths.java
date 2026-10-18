package com.sessioniq.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DataPaths {
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

	public final Path root;
	public final Path manifest;
	public final Path config;
	public final Path themes;
	public final Path state;
	public final Path sessions;
	public final Path chat;
	public final Path samples;
	public final Path servers;
	public final Path registry;
	public final Path backups;
	public final Path exports;
	public final Path quarantine;
	public final Path readme;
	public final Path online;

	public DataPaths(Path root) {
		this.root = root.toAbsolutePath().normalize();
		this.manifest = this.root.resolve("manifest.json");
		this.config = this.root.resolve("config.json");
		this.themes = this.root.resolve("themes.json");
		this.state = this.root.resolve("state.json");
		this.sessions = this.root.resolve("sessions");
		this.chat = this.root.resolve("chat");
		this.samples = this.root.resolve("samples");
		this.servers = this.root.resolve("servers");
		this.registry = this.servers.resolve("registry.json");
		this.backups = this.root.resolve("backups");
		this.exports = this.root.resolve("exports");
		this.quarantine = this.root.resolve("quarantine");
		this.readme = this.root.resolve("README.txt");
		this.online = this.root.resolve("online.json");
	}

	public Path sessionFile(String sessionId, long startedAt) {
		return sessions.resolve(MONTH.format(Instant.ofEpochMilli(startedAt))).resolve(sessionId + ".json");
	}

	public Path chatDir(String sessionId) {
		return chat.resolve(sessionId);
	}

	public Path samplesFile(String sessionId) {
		return samples.resolve(sessionId + ".jsonl");
	}
}
