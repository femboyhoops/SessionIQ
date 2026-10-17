package com.sessioniq;

import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.track.ServerIdentity;
import com.sessioniq.util.Ids;

public final class TestData {
	private TestData() {
	}

	public static SessionRecord session(long start, long runtimeMs) {
		SessionRecord s = new SessionRecord();
		s.id = Ids.session(start);
		s.startedAt = start;
		s.runtimeMs = runtimeMs;
		s.endedAt = start + runtimeMs;
		s.lastCheckpointAt = start + runtimeMs;
		s.status = SessionRecord.STATUS_COMPLETED;
		s.mcVersion = "1.21.11";
		s.modVersion = "test";
		return s;
	}

	public static VisitRecord visit(SessionRecord s, String host, int port, long joined, long durationMs) {
		VisitRecord v = new VisitRecord();
		v.id = Ids.visit();
		v.kind = VisitRecord.KIND_MULTIPLAYER;
		v.host = host;
		v.port = port;
		v.name = host;
		v.address = host + ":" + port;
		v.serverKey = ServerIdentity.multiplayerKey(host, port);
		v.joinedAt = joined;
		v.durationMs = durationMs;
		v.leftAt = joined + durationMs;
		s.visits.add(v);
		s.recomputeTotals();
		return v;
	}

	public static VisitRecord world(SessionRecord s, String folder, long joined, long durationMs) {
		VisitRecord v = new VisitRecord();
		v.id = Ids.visit();
		v.kind = VisitRecord.KIND_SINGLEPLAYER;
		v.name = folder;
		v.serverKey = ServerIdentity.worldKey(folder);
		v.joinedAt = joined;
		v.durationMs = durationMs;
		v.leftAt = joined + durationMs;
		s.visits.add(v);
		s.recomputeTotals();
		return v;
	}
}
