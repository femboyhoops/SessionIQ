package com.sessioniq.track;

import com.sessioniq.SessionIQ;
import com.sessioniq.config.SiqConfig;
import com.sessioniq.model.ActiveState;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MarkerRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.util.Ids;
import com.sessioniq.util.Json;
import com.sessioniq.util.SiqLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class SessionTracker {
	private static final long NANOS_PER_MS = 1_000_000L;
	private static final long CLASSIFY_DELAY_NS = 750 * NANOS_PER_MS;
	private static final long RECONNECT_WINDOW_MS = 30 * 60 * 1000L;
	private static final long ATTEMPT_TIMEOUT_NS = 10L * 60 * 1000 * NANOS_PER_MS;

	private final SessionIQ siq;
	private final PerfSampler perf;

	private SessionRecord session;
	private long sessionStartNano;
	private long lastCheckpointNano;

	private VisitRecord visit;
	private long visitStartNano;
	private long pausedNanos;
	private long pauseStartNano = -1;
	private ClientConnection visitConnection;
	private long connectionClosedSinceNano = -1;
	private long worldMissingSinceNano = -1;

	private Attempt attempt;
	private final List<Pending> pending = new ArrayList<>();
	private ReconnectRef lastUnexpected;
	private boolean stopped;

	public SessionTracker(SessionIQ siq) {
		this.siq = siq;
		this.perf = new PerfSampler(siq, this);
	}

	private SiqConfig config() {
		return siq.config.get();
	}

	public void beginSession() {
		if (session != null || !config().trackSessions || stopped) {
			return;
		}
		long now = System.currentTimeMillis();
		session = new SessionRecord();
		session.id = Ids.session(now);
		while (siq.sessions.contains(session.id)) {
			session.id = Ids.session(now);
		}
		session.startedAt = now;
		session.lastCheckpointAt = now;
		session.mcVersion = siq.mcVersion;
		session.modVersion = siq.modVersion;
		session.status = SessionRecord.STATUS_ACTIVE;
		sessionStartNano = System.nanoTime();
		lastCheckpointNano = sessionStartNano;
		perf.reset(sessionStartNano);
		siq.sessions.setActive(session);
		siq.sessions.save(session);
		writeState();
		SiqLog.info("SessionIQ recording session {}", session.id);
	}

	public void endSession(String note, boolean clientShutdown) {
		if (session == null) {
			return;
		}
		if (visit != null) {
			if (clientShutdown) {
				ConnectionEvidence.markClientShutdown(visitConnection);
				endVisit(VisitRecord.END_GAME_CLOSED, true);
			} else {
				endVisitWithoutEvent(VisitRecord.END_RECORDING_STOPPED);
			}
		}
		finalizeAllPending();
		if (attempt != null) {
			attempt = null;
		}
		updateLive();
		session.endedAt = System.currentTimeMillis();
		session.status = SessionRecord.STATUS_COMPLETED;
		session.endNote = note;
		siq.sessions.save(session);
		siq.chat.flush();
		siq.samples.flush();
		clearState();
		siq.sessions.setActive(null);
		SiqLog.info("SessionIQ session {} completed", session.id);
		session = null;
	}

	public void shutdown() {
		if (stopped) {
			return;
		}
		endSession(null, true);
		stopped = true;
	}

	public void emergencyCheckpoint() {
		if (stopped || session == null) {
			return;
		}
		try {
			updateLive();
			String json = Json.write(session, true);
			com.sessioniq.util.AtomicFiles.writeString(siq.paths.sessionFile(session.id, session.startedAt), json);
		} catch (Throwable t) {
			SiqLog.warn("SessionIQ emergency checkpoint failed: {}", t.toString());
		}
	}

	public void onConfigChanged() {
		SiqConfig c = config();
		if (!c.trackSessions && session != null) {
			endSession("Recording was turned off in settings", false);
		} else if (c.trackSessions && session == null && !stopped) {
			beginSession();
		}
		if (visit != null && (visit.isMultiplayer() ? !c.trackMultiplayer : !c.trackSingleplayer)) {
			endVisitWithoutEvent(VisitRecord.END_RECORDING_STOPPED);
			checkpoint();
		}
	}

	public void onHistoryLoaded() {
		long now = System.currentTimeMillis();
		for (SessionRecord s : siq.sessions.sorted()) {
			if (session != null && s.id.equals(session.id)) {
				continue;
			}
			if (s.endEstimate() < now - RECONNECT_WINDOW_MS) {
				break;
			}
			DisconnectRecord latest = null;
			for (DisconnectRecord d : s.disconnects) {
				if (d.visitId != null && (latest == null || d.at > latest.at)) {
					latest = d;
				}
			}
			if (latest != null) {
				if (latest.isUnexpected() && latest.nextConnectionAt == null && now - latest.at < RECONNECT_WINDOW_MS) {
					lastUnexpected = new ReconnectRef(s.id, latest.id, latest.at, -1, latest.serverKey);
				}
				break;
			}
		}
	}

	public void tick(MinecraftClient client) {
		long now = System.nanoTime();
		processPending(now, false);
		if (session == null) {
			return;
		}
		if (visit != null) {
			trackPause(client, now);
			detectLostVisit(client, now);
		}
		if (attempt != null && now - attempt.startNano > ATTEMPT_TIMEOUT_NS) {
			DisconnectClassifier.Facts facts = new DisconnectClassifier.Facts();
			facts.phase = "connect";
			recordFailedAttempt(facts, "No outcome was observed for this connection attempt");
		}
		perf.tick(client, now);
		if (now - lastCheckpointNano >= config().checkpointSeconds * 1_000_000_000L) {
			checkpoint();
		}
	}

	private void trackPause(MinecraftClient client, long now) {
		boolean paused = !visit.isMultiplayer() && client.isPaused();
		if (paused && pauseStartNano < 0) {
			pauseStartNano = now;
		} else if (!paused && pauseStartNano >= 0) {
			pausedNanos += now - pauseStartNano;
			pauseStartNano = -1;
		}
	}

	private void detectLostVisit(MinecraftClient client, long now) {
		if (visitConnection != null && !visitConnection.isOpen()) {
			if (connectionClosedSinceNano < 0) {
				connectionClosedSinceNano = now;
			} else if (now - connectionClosedSinceNano > 2_000 * NANOS_PER_MS) {
				onConnectionEnded(visitConnection);
			}
		} else {
			connectionClosedSinceNano = -1;
		}
		if (visit != null && client.world == null && client.getNetworkHandler() == null) {
			if (worldMissingSinceNano < 0) {
				worldMissingSinceNano = now;
			} else if (now - worldMissingSinceNano > 5_000 * NANOS_PER_MS && (visitConnection == null || !visitConnection.isOpen())) {
				onConnectionEnded(visitConnection);
			}
		} else {
			worldMissingSinceNano = -1;
		}
	}

	public void checkpoint() {
		if (session == null) {
			return;
		}
		updateLive();
		siq.sessions.save(session);
		siq.sessions.changed();
		siq.chat.flush();
		siq.samples.flush();
		writeState();
		lastCheckpointNano = System.nanoTime();
	}

	public void updateLive() {
		if (session == null) {
			return;
		}
		long now = System.nanoTime();
		session.runtimeMs = (now - sessionStartNano) / NANOS_PER_MS;
		if (visit != null) {
			visit.durationMs = (now - visitStartNano) / NANOS_PER_MS;
			long paused = pausedNanos + (pauseStartNano >= 0 ? now - pauseStartNano : 0);
			visit.pausedMs = Math.min(visit.durationMs, paused / NANOS_PER_MS);
		}
		session.recomputeTotals();
		session.lastCheckpointAt = System.currentTimeMillis();
	}

	private void writeState() {
		if (session == null) {
			return;
		}
		ActiveState state = new ActiveState();
		state.sessionId = session.id;
		state.pid = ProcessHandle.current().pid();
		state.startedAt = session.startedAt;
		state.heartbeatAt = System.currentTimeMillis();
		state.activeVisitId = visit == null ? null : visit.id;
		siq.storage.writeAtomic(siq.paths.state, Json.write(state, true));
	}

	private void clearState() {
		Path state = siq.paths.state;
		siq.storage.runIo(() -> {
			try {
				java.nio.file.Files.deleteIfExists(state);
			} catch (java.io.IOException e) {
				siq.storage.report("Could not clear state.json", e.toString());
			}
		});
	}

	public void onConnectAttempt(ServerInfo info, ServerAddress address, boolean transfer) {
		if (session == null || !config().trackMultiplayer) {
			attempt = null;
			return;
		}
		if (attempt != null) {
			DisconnectClassifier.Facts facts = new DisconnectClassifier.Facts();
			facts.phase = "connect";
			recordFailedAttempt(facts, "A new connection attempt started before this one finished");
		}
		Attempt a = new Attempt();
		a.name = info == null ? null : info.name;
		a.address = info == null ? null : info.address;
		a.host = ServerIdentity.normalizeHost(address.getAddress());
		a.port = address.getPort();
		a.kind = info != null && info.isRealm() ? VisitRecord.KIND_REALMS : info != null && info.isLocal() ? VisitRecord.KIND_LAN : VisitRecord.KIND_MULTIPLAYER;
		a.serverKey = VisitRecord.KIND_REALMS.equals(a.kind) ? ServerIdentity.realmsKey(a.name) : ServerIdentity.multiplayerKey(a.host, a.port);
		if (transfer) {
			a.name = a.host + (a.port == ServerIdentity.DEFAULT_PORT ? "" : ":" + a.port);
			a.address = a.name;
		}
		a.startedAt = System.currentTimeMillis();
		a.startNano = System.nanoTime();
		a.transfer = transfer;
		session.connectionAttempts++;
		if (lastUnexpected != null && !transfer && a.startedAt - lastUnexpected.at < RECONNECT_WINDOW_MS) {
			a.reconnect = true;
			session.reconnectAttempts++;
			editDisconnect(lastUnexpected, d -> d.reconnectAttempts++);
		}
		attempt = a;
		SiqLog.debug("Connection attempt to {}", a.serverKey);
	}

	public void onListenerDisconnected(ClientConnection connection, DisconnectionInfo info, String phase) {
		if (visit != null && connection == visitConnection) {
			onConnectionEnded(connection);
			return;
		}
		if (attempt != null && ("login".equals(phase) || "configuration".equals(phase))) {
			recordFailedAttempt(ConnectionEvidence.facts(connection, phase), null);
		}
	}

	public void onDisconnectedScreen(Text title, DisconnectionInfo info) {
		if (attempt == null) {
			return;
		}
		DisconnectClassifier.Facts facts = new DisconnectClassifier.Facts();
		facts.phase = "connect";
		ConnectionEvidence.describe(info == null ? null : info.reason(), facts);
		recordFailedAttempt(facts, null);
	}

	public void onConnectScreenClosed(boolean cancelled) {
		if (attempt != null && cancelled) {
			DisconnectClassifier.Facts facts = new DisconnectClassifier.Facts();
			facts.phase = "connect";
			facts.userCancelled = true;
			recordFailedAttempt(facts, null);
		}
	}

	private void recordFailedAttempt(DisconnectClassifier.Facts facts, String extraEvidence) {
		Attempt a = attempt;
		attempt = null;
		if (a == null || session == null) {
			return;
		}
		if (!"login".equals(facts.phase) && !"configuration".equals(facts.phase)) {
			facts.phase = "connect";
		}
		DisconnectClassifier.Result r = DisconnectClassifier.classify(facts);
		DisconnectRecord d = new DisconnectRecord();
		d.id = Ids.disconnect();
		d.at = System.currentTimeMillis();
		d.sessionId = session.id;
		d.serverKey = a.serverKey;
		d.serverName = a.name;
		d.address = a.address;
		d.phase = facts.phase;
		d.category = r.category().name();
		d.certainty = r.certainty().name();
		d.reason = facts.reasonText;
		d.reasonKey = facts.reasonKey;
		d.details = r.details();
		d.evidence.addAll(r.evidence());
		if (extraEvidence != null) {
			d.evidence.add(extraEvidence);
		}
		d.reconnectAttempt = a.reconnect;
		if (r.category() != DisconnectCategory.CONNECTION_ABORTED) {
			session.failedConnections++;
		}
		session.disconnects.add(d);
		checkpoint();
	}

	public void onJoin(ClientPlayNetworkHandler handler, MinecraftClient client) {
		if (session == null) {
			attempt = null;
			return;
		}
		ClientConnection connection = handler.getConnection();
		if (visit != null) {
			if (visitConnection == connection) {
				return;
			}
			onConnectionEnded(visitConnection);
		}
		IntegratedServer integrated = client.getServer();
		VisitRecord v = new VisitRecord();
		v.id = Ids.visit();
		v.joinedAt = System.currentTimeMillis();
		v.open = true;
		if (integrated != null && client.isInSingleplayer()) {
			if (!config().trackSingleplayer) {
				attempt = null;
				return;
			}
			v.kind = VisitRecord.KIND_SINGLEPLAYER;
			String folder;
			try {
				Path root = integrated.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
				folder = root.getFileName().toString();
			} catch (Exception e) {
				folder = integrated.getSaveProperties().getLevelName();
			}
			v.serverKey = ServerIdentity.worldKey(folder);
			v.name = integrated.getSaveProperties().getLevelName();
		} else {
			if (!config().trackMultiplayer) {
				attempt = null;
				return;
			}
			Attempt a = attempt;
			ServerInfo info = client.getCurrentServerEntry();
			if (a != null) {
				v.kind = a.kind;
				v.serverKey = a.serverKey;
				v.name = a.name;
				v.address = a.address;
				v.host = a.host;
				v.port = a.port;
				v.transfer = a.transfer;
			} else if (info != null) {
				ServerAddress parsed = ServerAddress.parse(info.address);
				v.kind = info.isRealm() ? VisitRecord.KIND_REALMS : info.isLocal() ? VisitRecord.KIND_LAN : VisitRecord.KIND_MULTIPLAYER;
				v.host = ServerIdentity.normalizeHost(parsed.getAddress());
				v.port = parsed.getPort();
				v.name = info.name;
				v.address = info.address;
				v.serverKey = info.isRealm() ? ServerIdentity.realmsKey(info.name) : ServerIdentity.multiplayerKey(v.host, v.port);
			} else {
				SocketAddress remote = connection.getAddress();
				v.kind = VisitRecord.KIND_MULTIPLAYER;
				if (remote instanceof InetSocketAddress inet) {
					v.host = ServerIdentity.normalizeHost(inet.getHostString());
					v.port = inet.getPort();
				} else {
					v.host = "unknown";
					v.port = ServerIdentity.DEFAULT_PORT;
				}
				v.name = v.host;
				v.address = v.host + ":" + v.port;
				v.serverKey = ServerIdentity.multiplayerKey(v.host, v.port);
			}
			if (v.name == null || v.name.isBlank()) {
				v.name = v.host;
			}
		}
		attempt = null;
		visit = v;
		visitConnection = connection;
		visitStartNano = System.nanoTime();
		pausedNanos = 0;
		pauseStartNano = -1;
		connectionClosedSinceNano = -1;
		worldMissingSinceNano = -1;
		session.visits.add(v);
		perf.onVisitStart(visitStartNano);

		if (lastUnexpected != null && v.isMultiplayer()) {
			ReconnectRef ref = lastUnexpected;
			lastUnexpected = null;
			long gap = ref.nano >= 0 && ref.sessionId.equals(session.id) ? (visitStartNano - ref.nano) / NANOS_PER_MS : v.joinedAt - ref.at;
			String visitId = v.id;
			boolean same = v.serverKey.equals(ref.serverKey);
			editDisconnect(ref, d -> {
				d.nextConnectionAt = v.joinedAt;
				d.timeToReconnectMs = Math.max(0, gap);
				d.returnedToSameServer = same;
				d.nextVisitId = visitId;
			});
		}
		checkpoint();
		SiqLog.debug("Joined {} ({})", v.serverKey, v.kind);
	}

	public void onConnectionEnded(ClientConnection connection) {
		if (visit == null || (connection != null && visitConnection != null && connection != visitConnection)) {
			return;
		}
		endVisit(VisitRecord.END_DISCONNECTED, false);
	}

	private void endVisit(String endReason, boolean immediate) {
		VisitRecord v = closeVisit(endReason);
		DisconnectRecord d = new DisconnectRecord();
		d.id = Ids.disconnect();
		d.at = v.leftAt;
		d.sessionId = session.id;
		d.visitId = v.id;
		d.serverKey = v.serverKey;
		d.serverName = v.name;
		d.address = v.address;
		Pending p = new Pending();
		p.record = d;
		p.visit = v;
		p.connection = visitConnection;
		p.dueNano = immediate ? 0 : System.nanoTime() + CLASSIFY_DELAY_NS;
		p.endNano = System.nanoTime();
		visitConnection = null;
		pending.add(p);
		if (immediate) {
			processPending(System.nanoTime(), true);
		}
		checkpoint();
	}

	private void endVisitWithoutEvent(String endReason) {
		closeVisit(endReason);
		visitConnection = null;
	}

	private VisitRecord closeVisit(String endReason) {
		updateLive();
		VisitRecord v = visit;
		v.open = false;
		v.leftAt = System.currentTimeMillis();
		v.endReason = endReason;
		visit = null;
		pauseStartNano = -1;
		perf.onVisitEnd();
		session.recomputeTotals();
		return v;
	}

	private void processPending(long now, boolean force) {
		if (pending.isEmpty()) {
			return;
		}
		Iterator<Pending> it = pending.iterator();
		boolean changed = false;
		while (it.hasNext()) {
			Pending p = it.next();
			boolean ready = force || now >= p.dueNano || ConnectionEvidence.hasReason(p.connection) && now >= p.dueNano - CLASSIFY_DELAY_NS / 2;
			if (!ready) {
				continue;
			}
			it.remove();
			classify(p);
			changed = true;
		}
		if (changed && session != null) {
			siq.sessions.save(session);
			siq.sessions.changed();
		}
	}

	private void finalizeAllPending() {
		processPending(System.nanoTime(), true);
	}

	private void classify(Pending p) {
		DisconnectClassifier.Facts facts = ConnectionEvidence.facts(p.connection, "play");
		DisconnectClassifier.Result r = DisconnectClassifier.classify(facts);
		VisitRecord v = p.visit;
		boolean intentional = r.category() == DisconnectCategory.INTENTIONAL;
		if (intentional) {
			v.endReason = facts.clientShutdown ? VisitRecord.END_GAME_CLOSED : VisitRecord.END_LEFT;
		}
		if (!v.isMultiplayer() && (intentional || r.category() == DisconnectCategory.UNKNOWN || "menu.savingLevel".equals(facts.reasonKey))) {
			if (!VisitRecord.END_GAME_CLOSED.equals(v.endReason)) {
				v.endReason = VisitRecord.END_LEFT;
			}
			return;
		}
		DisconnectRecord d = p.record;
		d.phase = facts.phase;
		d.category = r.category().name();
		d.certainty = r.certainty().name();
		d.reason = facts.serverPacket && facts.reasonText != null ? facts.reasonText
			: facts.displayedText != null && !facts.displayedText.isBlank() ? facts.displayedText : facts.reasonText;
		d.reasonKey = facts.reasonKey;
		d.details = r.details();
		d.reportFile = facts.reportFile;
		d.evidence.addAll(r.evidence());
		if (facts.firstSignalText != null) {
			d.evidence.add("The connection close was observed before the server's message was processed (\"" + facts.firstSignalText + "\")");
		}
		if (facts.displayedText != null && d.reason != null && !d.reason.equals(facts.displayedText)) {
			d.evidence.add("The game displayed: \"" + facts.displayedText + "\"");
		} else if (!facts.serverPacket && facts.reasonText != null && d.reason != null && !d.reason.equals(facts.reasonText)) {
			d.evidence.add("First disconnect signal observed: \"" + facts.reasonText + "\"" + (facts.reasonKey != null ? " (" + facts.reasonKey + ")" : ""));
		}
		if (session == null) {
			return;
		}
		session.disconnects.add(d);
		v.disconnectId = d.id;
		if (d.isUnexpected() && v.isMultiplayer()) {
			lastUnexpected = new ReconnectRef(session.id, d.id, d.at, p.endNano, d.serverKey);
		} else if (!v.isMultiplayer() || r.category() == DisconnectCategory.INTENTIONAL || r.category() == DisconnectCategory.TRANSFER) {
			lastUnexpected = null;
		}
	}

	private void editDisconnect(ReconnectRef ref, java.util.function.Consumer<DisconnectRecord> change) {
		SessionRecord owner = siq.sessions.editable(ref.sessionId);
		if (owner == null) {
			return;
		}
		DisconnectRecord d = owner.disconnect(ref.disconnectId);
		if (d == null) {
			return;
		}
		change.accept(d);
		siq.sessions.commit(owner);
	}

	public boolean recordChat(ChatRecord record) {
		if (session == null || visit == null) {
			return false;
		}
		record.id = Ids.chat();
		record.sessionId = session.id;
		record.visitId = visit.id;
		record.serverKey = visit.serverKey;
		visit.chatCount++;
		session.chatCount++;
		siq.chat.append(record);
		return true;
	}

	public void recordDeath(String message) {
		if (session == null || visit == null || !config().recordDeaths) {
			return;
		}
		MarkerRecord m = new MarkerRecord();
		m.id = Ids.marker();
		m.at = System.currentTimeMillis();
		m.type = MarkerRecord.TYPE_DEATH;
		m.visitId = visit.id;
		m.serverKey = visit.serverKey;
		m.text = message == null ? null : Sanitize.oneLine(message, 300);
		session.markers.add(m);
		checkpoint();
	}

	public SessionRecord session() {
		return session;
	}

	public VisitRecord visit() {
		return visit;
	}

	public boolean isRecording() {
		return session != null;
	}

	public boolean isConnecting() {
		return attempt != null;
	}

	public PerfSampler perf() {
		return perf;
	}

	public String describeMode() {
		if (session == null) {
			return config().trackSessions ? "Starting" : "Recording off";
		}
		if (visit == null) {
			return attempt != null ? "Connecting" : "In menus";
		}
		return visit.isMultiplayer() ? "Connected" : "In world";
	}

	public String currentPlaceName() {
		if (visit == null) {
			return null;
		}
		String nick = siq.servers.nickname(visit.serverKey);
		return nick != null ? nick : visit.name;
	}

	private static final class Attempt {
		String name;
		String address;
		String host;
		int port;
		String kind;
		String serverKey;
		long startedAt;
		long startNano;
		boolean transfer;
		boolean reconnect;
	}

	private static final class Pending {
		DisconnectRecord record;
		VisitRecord visit;
		ClientConnection connection;
		long dueNano;
		long endNano;
	}

	private record ReconnectRef(String sessionId, String disconnectId, long at, long nano, String serverKey) {
	}
}
