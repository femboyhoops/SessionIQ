package com.sessioniq.track;

import com.sessioniq.SessionIQ;
import com.sessioniq.model.MetricSummary;
import com.sessioniq.model.SampleRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

public final class PerfSampler {
	private static final double MB = 1024.0 * 1024.0;

	private final SessionIQ siq;
	private final SessionTracker tracker;
	private long nextPingNano;
	private long nextMemoryNano;
	private Integer lastPing;
	private long lastPingAt;
	private boolean pingUnavailable;

	PerfSampler(SessionIQ siq, SessionTracker tracker) {
		this.siq = siq;
		this.tracker = tracker;
	}

	void reset(long now) {
		nextMemoryNano = now + 5_000_000_000L;
		nextPingNano = now;
	}

	void onVisitStart(long now) {
		nextPingNano = now + 5_000_000_000L;
		lastPing = null;
		pingUnavailable = false;
	}

	void onVisitEnd() {
		lastPing = null;
	}

	void tick(MinecraftClient client, long now) {
		var config = siq.config.get();
		SessionRecord session = tracker.session();
		if (session == null) {
			return;
		}
		VisitRecord visit = tracker.visit();
		if (config.recordPing && visit != null && visit.isMultiplayer() && now >= nextPingNano) {
			nextPingNano = now + config.pingIntervalSec * 1_000_000_000L;
			samplePing(client, session, visit);
		}
		if (config.recordMemory && now >= nextMemoryNano) {
			nextMemoryNano = now + config.memoryIntervalSec * 1_000_000_000L;
			sampleMemory(session, visit);
		}
	}

	private void samplePing(MinecraftClient client, SessionRecord session, VisitRecord visit) {
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null || client.player == null) {
			pingUnavailable = true;
			return;
		}
		PlayerListEntry entry = handler.getPlayerListEntry(client.player.getUuid());
		if (entry == null || entry.getLatency() <= 0) {
			pingUnavailable = true;
			return;
		}
		int latency = entry.getLatency();
		pingUnavailable = false;
		lastPing = latency;
		lastPingAt = System.currentTimeMillis();
		if (visit.ping == null) {
			visit.ping = new MetricSummary();
		}
		visit.ping.add(latency);
		SampleRecord s = new SampleRecord();
		s.at = lastPingAt;
		s.kind = SampleRecord.KIND_PING;
		s.visitId = visit.id;
		s.value = latency;
		siq.samples.append(session.id, s);
	}

	private void sampleMemory(SessionRecord session, VisitRecord visit) {
		Runtime rt = Runtime.getRuntime();
		long used = rt.totalMemory() - rt.freeMemory();
		long max = rt.maxMemory();
		double usedMb = used / MB;
		if (session.memoryUsedMb == null) {
			session.memoryUsedMb = new MetricSummary();
		}
		session.memoryUsedMb.add(Math.round(usedMb * 10) / 10.0);
		if (max != Long.MAX_VALUE) {
			session.memoryMaxMb = Math.round(max / MB);
		}
		SampleRecord s = new SampleRecord();
		s.at = System.currentTimeMillis();
		s.kind = SampleRecord.KIND_MEMORY;
		s.visitId = visit == null ? null : visit.id;
		s.value = Math.round(usedMb * 10) / 10.0;
		s.max = max == Long.MAX_VALUE ? null : (double) Math.round(max / MB);
		siq.samples.append(session.id, s);
	}

	public Integer lastPing() {
		return lastPing;
	}

	public boolean pingUnavailable() {
		return pingUnavailable;
	}
}
