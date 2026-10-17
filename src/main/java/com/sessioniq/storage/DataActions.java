package com.sessioniq.storage;

import com.sessioniq.SessionIQ;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.track.ServerIdentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class DataActions {
	private DataActions() {
	}

	public record Result(String message) {
	}

	public static void deleteSession(SessionIQ siq, String sessionId) {
		siq.chat.deleteSession(sessionId);
		siq.samples.deleteSession(sessionId);
		siq.sessions.delete(sessionId);
		siq.bumpRevision();
	}

	public static CompletableFuture<String> deleteServerHistory(SessionIQ siq, String groupKey) {
		boolean merge = siq.config.get().mergePorts;
		VisitRecord live = siq.tracker.visit();
		Map<String, Set<String>> visitsBySession = new HashMap<>();
		int visits = 0;
		int disconnects = 0;
		for (SessionRecord original : new ArrayList<>(siq.sessions.sorted())) {
			boolean touches = original.visits.stream().anyMatch(v -> groupKey.equals(ServerIdentity.groupKey(v, merge)) && v != live)
				|| original.disconnects.stream().anyMatch(d -> d.serverKey != null && groupKey.equals(ServerIdentity.groupKey(d.serverKey, merge)));
			if (!touches) {
				continue;
			}
			SessionRecord s = siq.sessions.editable(original.id);
			Set<String> removed = new HashSet<>();
			for (VisitRecord v : s.visits) {
				if (groupKey.equals(ServerIdentity.groupKey(v, merge)) && (live == null || !v.id.equals(live.id))) {
					removed.add(v.id);
				}
			}
			visits += removed.size();
			s.visits.removeIf(v -> removed.contains(v.id));
			int before = s.disconnects.size();
			s.disconnects.removeIf(d -> d.serverKey != null && groupKey.equals(ServerIdentity.groupKey(d.serverKey, merge))
				&& (d.visitId == null || removed.contains(d.visitId)));
			disconnects += before - s.disconnects.size();
			s.markers.removeIf(m -> m.visitId != null && removed.contains(m.visitId));
			clearDanglingReferences(s, removed);
			s.chatCount = s.visits.stream().mapToInt(v -> v.chatCount).sum();
			s.recomputeTotals();
			siq.sessions.commit(s);
			if (!removed.isEmpty()) {
				visitsBySession.put(s.id, removed);
			}
		}
		if (live == null || !groupKey.equals(ServerIdentity.groupKey(live, merge))) {
			siq.servers.remove(groupKey);
		}
		for (Map.Entry<String, Set<String>> e : visitsBySession.entrySet()) {
			siq.samples.deleteVisits(e.getKey(), e.getValue());
		}
		siq.bumpRevision();
		int v = visits;
		int d = disconnects;
		return siq.chat.deleteWhere(visitsBySession.keySet(), r -> r.visitId != null && visitsBySession.getOrDefault(r.sessionId, Set.of()).contains(r.visitId))
			.thenApply(removedChat -> v + " visit(s), " + d + " disconnect event(s) and " + removedChat.size() + " chat message(s) deleted");
	}

	private static void clearDanglingReferences(SessionRecord s, Set<String> removedVisits) {
		for (DisconnectRecord d : s.disconnects) {
			if (d.nextVisitId != null && removedVisits.contains(d.nextVisitId)) {
				d.nextVisitId = null;
			}
		}
	}

	public static void deleteDisconnect(SessionIQ siq, String sessionId, String disconnectId) {
		SessionRecord s = siq.sessions.editable(sessionId);
		if (s == null) {
			return;
		}
		s.disconnects.removeIf(d -> d.id.equals(disconnectId));
		for (VisitRecord v : s.visits) {
			if (disconnectId.equals(v.disconnectId)) {
				v.disconnectId = null;
			}
		}
		siq.sessions.commit(s);
		siq.bumpRevision();
	}

	public static CompletableFuture<Integer> deleteChat(SessionIQ siq, List<ChatRecord> messages) {
		Map<String, Set<String>> ids = new HashMap<>();
		for (ChatRecord r : messages) {
			ids.computeIfAbsent(r.sessionId, k -> new HashSet<>()).add(r.id);
		}
		return siq.chat.deleteMessages(ids).thenApplyAsync(removed -> {
			applyChatRemoval(siq, removed);
			return removed.size();
		}, SessionIQ.mainThread());
	}

	private static void applyChatRemoval(SessionIQ siq, List<ChatRecord> removed) {
		Map<String, Map<String, Integer>> counts = new HashMap<>();
		for (ChatRecord r : removed) {
			counts.computeIfAbsent(r.sessionId, k -> new HashMap<>()).merge(r.visitId == null ? "" : r.visitId, 1, Integer::sum);
		}
		for (Map.Entry<String, Map<String, Integer>> e : counts.entrySet()) {
			SessionRecord s = siq.sessions.editable(e.getKey());
			if (s == null) {
				continue;
			}
			int total = 0;
			for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
				total += v.getValue();
				VisitRecord visit = s.visit(v.getKey());
				if (visit != null) {
					visit.chatCount = Math.max(0, visit.chatCount - v.getValue());
				}
			}
			s.chatCount = Math.max(0, s.chatCount - total);
			siq.sessions.commit(s);
		}
		siq.bumpRevision();
	}

	public static CompletableFuture<Void> clearAllChat(SessionIQ siq) {
		return siq.chat.deleteAll().thenRunAsync(() -> {
			for (SessionRecord original : new ArrayList<>(siq.sessions.sorted())) {
				if (original.chatCount == 0 && original.visits.stream().allMatch(v -> v.chatCount == 0)) {
					continue;
				}
				SessionRecord s = siq.sessions.editable(original.id);
				s.chatCount = 0;
				for (VisitRecord v : s.visits) {
					v.chatCount = 0;
				}
				siq.sessions.commit(s);
			}
			siq.bumpRevision();
		}, SessionIQ.mainThread());
	}

	public static CompletableFuture<Void> clearAllHistory(SessionIQ siq) {
		siq.tracker.endSession("History was cleared", false);
		for (SessionRecord s : new ArrayList<>(siq.sessions.sorted())) {
			siq.chat.deleteSession(s.id);
			siq.samples.deleteSession(s.id);
			siq.sessions.delete(s.id);
		}
		siq.servers.clear();
		return siq.chat.deleteAll().thenRunAsync(() -> {
			siq.bumpRevision();
			siq.tracker.beginSession();
		}, SessionIQ.mainThread());
	}

	public static void applyChatCounts(SessionIQ siq, Map<String, Map<String, Integer>> actual) {
		for (Map.Entry<String, Map<String, Integer>> e : actual.entrySet()) {
			SessionRecord s = siq.sessions.editable(e.getKey());
			if (s == null || s.isActive()) {
				continue;
			}
			int total = 0;
			for (VisitRecord v : s.visits) {
				v.chatCount = e.getValue().getOrDefault(v.id, 0);
			}
			for (int c : e.getValue().values()) {
				total += c;
			}
			s.chatCount = total;
			siq.sessions.commit(s);
		}
		siq.bumpRevision();
	}
}
