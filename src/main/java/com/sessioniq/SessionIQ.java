package com.sessioniq;

import com.sessioniq.backup.BackupService;
import com.sessioniq.config.ConfigManager;
import com.sessioniq.export.ExportService;
import com.sessioniq.model.ActiveState;
import com.sessioniq.model.ServerMeta;
import com.sessioniq.online.OnlineService;
import com.sessioniq.storage.ChatArchive;
import com.sessioniq.storage.DataPaths;
import com.sessioniq.storage.Migrations;
import com.sessioniq.storage.RecoveryManager;
import com.sessioniq.storage.SampleStore;
import com.sessioniq.storage.ServerRegistry;
import com.sessioniq.storage.SessionRepository;
import com.sessioniq.storage.StorageService;
import com.sessioniq.theme.ThemeManager;
import com.sessioniq.track.ChatCapture;
import com.sessioniq.track.SessionTracker;
import com.sessioniq.util.AtomicFiles;
import com.sessioniq.util.Json;
import com.sessioniq.util.SiqLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

public final class SessionIQ {
	public static final String MOD_ID = "sessioniq";
	private static volatile SessionIQ instance;

	public final DataPaths paths;
	public final Path gameDir;
	public final String modVersion;
	public final String mcVersion;
	public final ConfigManager config;
	public final ThemeManager themes;
	public final StorageService storage;
	public final SessionRepository sessions;
	public final ChatArchive chat;
	public final SampleStore samples;
	public final ServerRegistry servers;
	public final SessionTracker tracker;
	public final ChatCapture chatCapture;
	public final BackupService backups;
	public final ExportService exports;
	public final OnlineService online;

	private volatile boolean ready;
	private volatile String startupFailure;
	private final List<String> startupProblems = new CopyOnWriteArrayList<>();
	private RecoveryManager.Outcome recovery;
	private Migrations.Status schemaStatus;
	private boolean cleanShutdown;
	private long nextBackupCheckNano;
	private int chatFlushTicks;
	private int dataRevision;

	private SessionIQ(Path root, Path gameDir) {
		this.paths = new DataPaths(root);
		this.gameDir = gameDir;
		this.modVersion = FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		this.mcVersion = SharedConstants.getGameVersion().name();
		try {
			Files.createDirectories(paths.root);
		} catch (IOException e) {
			startupProblems.add("Could not create the data directory: " + StorageService.describe(e));
		}
		this.config = new ConfigManager(paths.config, paths.quarantine);
		config.load();
		SiqLog.setDiagnostics(config.get().diagnosticLogging);
		this.themes = new ThemeManager(paths.themes, paths.quarantine);
		themes.load(config.get().theme);
		this.storage = new StorageService();
		this.sessions = new SessionRepository(paths, storage);
		this.chat = new ChatArchive(paths, storage);
		this.samples = new SampleStore(paths, storage);
		this.servers = new ServerRegistry(paths, storage);
		this.backups = new BackupService(paths, storage, modVersion);
		this.exports = new ExportService(this);
		this.tracker = new SessionTracker(this);
		this.chatCapture = new ChatCapture(this);
		this.online = new OnlineService(this);
		online.load();
	}

	public static SessionIQ init() {
		String override = System.getProperty("sessioniq.dataDir");
		Path root = override != null && !override.isBlank() ? Path.of(override) : FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		SessionIQ siq = new SessionIQ(root, FabricLoader.getInstance().getGameDir());
		instance = siq;
		siq.start();
		Runtime.getRuntime().addShutdownHook(new Thread(siq::onJvmShutdown, "SessionIQ-ShutdownHook"));
		config().addListener(() -> siq.tracker.onConfigChanged());
		config().addListener(() -> siq.online.onConfigChanged());
		return siq;
	}

	private static ConfigManager config() {
		return instance.config;
	}

	public static SessionIQ get() {
		SessionIQ s = instance;
		if (s == null) {
			throw new IllegalStateException("SessionIQ is not initialized");
		}
		return s;
	}

	public static SessionIQ getOrNull() {
		return instance;
	}

	public static Executor mainThread() {
		return r -> MinecraftClient.getInstance().execute(r);
	}

	private record Startup(SessionRepository.LoadResult load, RecoveryManager.Outcome recovery, Map<String, ServerMeta> registry,
						   Migrations.Status schema, List<String> problems) {
	}

	private void start() {
		long pid = ProcessHandle.current().pid();
		storage.submitIo(() -> {
			List<String> problems = new ArrayList<>();
			Files.createDirectories(paths.root);
			writeReadme();
			Migrations.Status schema = Migrations.ensure(paths, modVersion, label -> backups.createNow(label, false).getFileName().toString());
			ActiveState previous = readState(problems);
			SessionRepository.LoadResult load = SessionRepository.loadAll(paths);
			RecoveryManager.Outcome rec = RecoveryManager.recover(paths, load.records(), load.files(), previous, gameDir, System.currentTimeMillis(), pid);
			Map<String, ServerMeta> registry = ServerRegistry.read(paths, problems);
			problems.addAll(load.problems());
			return new Startup(load, rec, registry, schema, problems);
		}).whenCompleteAsync((startup, error) -> {
			if (error != null) {
				startupFailure = "SessionIQ could not load its history: " + error;
				SiqLog.error("SessionIQ startup failed", error);
				storage.report("Startup failed", error.toString());
				ready = true;
				return;
			}
			sessions.publish(startup.load());
			servers.publish(startup.registry());
			recovery = startup.recovery();
			schemaStatus = startup.schema();
			startupProblems.addAll(startup.problems());
			startupProblems.addAll(startup.recovery().notes());
			if (startup.schema().problem() != null) {
				startupProblems.add(startup.schema().problem());
			}
			for (String p : startup.problems()) {
				storage.report("Startup", p);
			}
			tracker.onHistoryLoaded();
			sessions.logSummary();
			if (!startup.recovery().recoveredIds().isEmpty()) {
				SiqLog.info("SessionIQ recovered {} interrupted session(s)", startup.recovery().recoveredIds().size());
			}
			ready = true;
			nextBackupCheckNano = System.nanoTime() + 20_000_000_000L;
		}, mainThread());
		tracker.beginSession();
	}

	private ActiveState readState(List<String> problems) {
		if (!Files.exists(paths.state)) {
			return null;
		}
		try {
			return Json.read(Files.readString(paths.state, StandardCharsets.UTF_8), ActiveState.class);
		} catch (Exception e) {
			problems.add("state.json could not be read; interrupted sessions are still detected from their own files");
			return null;
		}
	}

	private void writeReadme() throws IOException {
		String text = """
			SessionIQ data directory
			========================
			Everything SessionIQ records stays in this folder. The optional online features
			(Settings > Online) upload only a summary: totals, playtime and joins per public server,
			and what your friends may see. Chat, disconnects and timelines are never uploaded.

			manifest.json            storage schema version and migration history
			config.json              preferences
			themes.json              selected and custom themes
			state.json               the session currently being recorded (used to detect crashes)
			sessions/YYYY-MM/ID.json one file per session: server visits, disconnect events, deaths, summaries
			chat/ID/part-NNNNN.jsonl received chat messages for a session, one JSON object per line
			samples/ID.jsonl         ping and memory samples, one JSON object per line
			servers/registry.json    server nicknames and groups you assigned
			online.json              anonymous install id and SessionIQ sign-in token (online features)
			backups/                 backup archives (zip + manifest with SHA-256 checksums)
			exports/                 default destination for exports
			quarantine/              copies of damaged files that could not be read (never deleted automatically)

			All timestamps are UTC epoch milliseconds. Durations are milliseconds measured with a monotonic clock.
			Outgoing commands and anything you type are never recorded.
			""";
		if (Files.exists(paths.readme) && text.equals(Files.readString(paths.readme, StandardCharsets.UTF_8))) {
			return;
		}
		AtomicFiles.writeString(paths.readme, text);
	}

	public void tick(MinecraftClient client) {
		tracker.tick(client);
		if (++chatFlushTicks >= 40) {
			chatFlushTicks = 0;
			chat.flush();
		}
		online.tick(client);
		long now = System.nanoTime();
		if (ready && now >= nextBackupCheckNano) {
			nextBackupCheckNano = now + 10L * 60 * 1_000_000_000L;
			maybeAutoBackup();
		}
	}

	private void maybeAutoBackup() {
		var c = config.get();
		if (!c.autoBackup) {
			return;
		}
		long intervalMs = c.backupIntervalHours * 3_600_000L;
		int keep = c.maxBackups;
		chat.flush();
		storage.runIo(() -> {
			long newest = backups.list().stream().filter(com.sessioniq.backup.BackupService.BackupInfo::auto)
				.mapToLong(com.sessioniq.backup.BackupService.BackupInfo::createdAt).max().orElse(0);
			if (System.currentTimeMillis() - newest < intervalMs) {
				return;
			}
			try {
				Path created = backups.createNow("automatic", true);
				backups.rotate(keep);
				SiqLog.info("SessionIQ created automatic backup {}", created.getFileName());
			} catch (IOException e) {
				storage.report("Automatic backup failed", StorageService.describe(e));
			}
		});
	}

	public void shutdown() {
		tracker.shutdown();
		online.shutdown();
		storage.shutdown(5000);
		cleanShutdown = true;
	}

	private void onJvmShutdown() {
		if (cleanShutdown) {
			return;
		}
		tracker.emergencyCheckpoint();
		storage.flush(2000);
	}

	public java.util.concurrent.CompletableFuture<com.sessioniq.backup.BackupService.RestoreResult> restore(Path archive,
			com.sessioniq.backup.BackupService.RestoreMode mode) {
		chat.flush();
		samples.flush();
		String protectedId = sessions.activeId();
		java.util.Set<String> existing = new java.util.HashSet<>();
		for (var s : sessions.sorted()) {
			existing.add(s.id);
		}
		return storage.submitIo(() -> {
			var result = backups.restore(archive, mode, protectedId, existing);
			List<String> problems = new ArrayList<>();
			var load = SessionRepository.loadAll(paths);
			var registry = ServerRegistry.read(paths, problems);
			return new Object[]{result, load, registry};
		}).thenApplyAsync(parts -> {
			@SuppressWarnings("unchecked")
			Map<String, ServerMeta> registry = (Map<String, ServerMeta>) parts[2];
			sessions.reset((SessionRepository.LoadResult) parts[1]);
			servers.replaceAll(registry);
			bumpRevision();
			return (com.sessioniq.backup.BackupService.RestoreResult) parts[0];
		}, mainThread());
	}

	public boolean isReady() {
		return ready;
	}

	public String startupFailure() {
		return startupFailure;
	}

	public List<String> startupProblems() {
		return startupProblems;
	}

	public RecoveryManager.Outcome recovery() {
		return recovery;
	}

	public Migrations.Status schemaStatus() {
		return schemaStatus;
	}

	public int dataRevision() {
		return dataRevision + sessions.revision();
	}

	public void bumpRevision() {
		dataRevision++;
	}
}
