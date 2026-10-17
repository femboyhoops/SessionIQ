package com.sessioniq.analytics;

import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.DisconnectCategory;
import com.sessioniq.model.DisconnectRecord;
import com.sessioniq.model.MarkerRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class TimelineBuilder {
	public static final long CHAT_GAP_MS = 3 * 60 * 1000L;

	private TimelineBuilder() {
	}

	public static List<TimelineEvent> build(SessionRecord s, List<ChatRecord> chat, Function<Long, String> durationFormatter,
											Function<String, String> serverName) {
		List<TimelineEvent> out = new ArrayList<>();
		TimelineEvent start = event(TimelineEvent.Type.SESSION_START, s.startedAt, s);
		start.title = "Session started";
		start.detail = "Minecraft " + nullTo(s.mcVersion, "?") + " · SessionIQ " + nullTo(s.modVersion, "?");
		out.add(start);

		for (VisitRecord v : s.visits) {
			String name = serverName.apply(v.serverKey);
			if (name == null) {
				name = v.name;
			}
			TimelineEvent join = event(TimelineEvent.Type.SERVER_JOIN, v.joinedAt, s);
			join.visitId = v.id;
			join.serverKey = v.serverKey;
			join.title = (v.isMultiplayer() ? "Joined " : "Entered world ") + name;
			join.detail = v.isMultiplayer() ? (v.transfer ? "Arrived through a server transfer" : nullTo(v.address, "")) : "Single-player";
			out.add(join);
			if (v.open) {
				continue;
			}
			DisconnectRecord d = v.disconnectId == null ? null : s.disconnect(v.disconnectId);
			long at = v.leftAt != null ? v.leftAt : v.joinedAt + v.durationMs;
			boolean problem = d != null && d.isUnexpected();
			TimelineEvent leave = event(problem ? TimelineEvent.Type.DISCONNECT : TimelineEvent.Type.SERVER_LEAVE, at, s);
			leave.visitId = v.id;
			leave.serverKey = v.serverKey;
			leave.disconnectId = d == null ? null : d.id;
			if (problem) {
				leave.severity = 2;
				leave.title = d.categoryEnum().label + " · " + name;
				leave.detail = (d.reason != null ? d.reason : "No reason was available") + " · after " + durationFormatter.apply(v.durationMs);
			} else {
				leave.title = (v.isMultiplayer() ? "Left " : "Left world ") + name;
				String why = switch (nullTo(v.endReason, "")) {
					case VisitRecord.END_GAME_CLOSED -> "Minecraft was closed";
					case VisitRecord.END_RECORDING_STOPPED -> "Recording was turned off";
					case VisitRecord.END_INTERRUPTED -> "Interrupted";
					default -> d != null && d.categoryEnum() == DisconnectCategory.TRANSFER ? "Transferred by the server" : "Disconnected";
				};
				leave.detail = why + " · " + durationFormatter.apply(v.durationMs);
				if (v.interrupted) {
					leave.severity = 1;
				}
			}
			out.add(leave);
		}

		for (DisconnectRecord d : s.disconnects) {
			if (d.visitId == null) {
				TimelineEvent f = event(TimelineEvent.Type.CONNECTION_FAILED, d.at, s);
				f.disconnectId = d.id;
				f.serverKey = d.serverKey;
				String name = serverName.apply(d.serverKey);
				boolean aborted = d.categoryEnum() == DisconnectCategory.CONNECTION_ABORTED;
				f.title = (aborted ? "Cancelled connecting to " : d.reconnectAttempt ? "Reconnect failed: " : "Could not connect to ") + nullTo(name != null ? name : d.serverName, "server");
				f.detail = d.reason != null ? d.reason : d.categoryEnum().label;
				f.severity = aborted ? 0 : 1;
				out.add(f);
			}
			if (d.nextConnectionAt != null && d.timeToReconnectMs != null && d.nextConnectionAt >= s.startedAt && d.nextConnectionAt <= s.endEstimate() + 1000) {
				TimelineEvent r = event(TimelineEvent.Type.RECONNECTED, d.nextConnectionAt, s);
				r.disconnectId = d.id;
				r.visitId = d.nextVisitId;
				r.serverKey = d.serverKey;
				r.title = Boolean.TRUE.equals(d.returnedToSameServer) ? "Reconnected" : "Connected to a different server";
				r.detail = "After " + durationFormatter.apply(d.timeToReconnectMs) + (d.reconnectAttempts > 0 ? " · " + d.reconnectAttempts + " attempt(s)" : "");
				out.add(r);
			}
		}

		for (MarkerRecord m : s.markers) {
			if (MarkerRecord.TYPE_DEATH.equals(m.type)) {
				TimelineEvent e = event(TimelineEvent.Type.DEATH, m.at, s);
				e.visitId = m.visitId;
				e.serverKey = m.serverKey;
				e.title = "Died";
				e.detail = m.text;
				e.severity = 1;
				out.add(e);
			}
		}

		if (chat != null && !chat.isEmpty()) {
			int groupStart = 0;
			for (int i = 1; i <= chat.size(); i++) {
				boolean split = i == chat.size()
					|| chat.get(i).at - chat.get(i - 1).at > CHAT_GAP_MS
					|| !eq(chat.get(i).visitId, chat.get(i - 1).visitId);
				if (split) {
					out.add(chatGroup(s, chat, groupStart, i));
					groupStart = i;
				}
			}
		}

		if (s.recovered) {
			TimelineEvent rec = event(TimelineEvent.Type.SESSION_RECOVERED, s.endEstimate(), s);
			rec.title = "Session recovered after an interruption";
			rec.detail = s.recoveryNote;
			rec.severity = 1;
			out.add(rec);
		}
		if (!s.isActive()) {
			TimelineEvent end = event(TimelineEvent.Type.SESSION_END, s.endEstimate(), s);
			end.title = SessionRecord.STATUS_INTERRUPTED.equals(s.status) ? "Session interrupted" : "Session ended";
			end.detail = "Application time " + durationFormatter.apply(s.runtimeMs) + (s.endNote != null ? " · " + s.endNote : "");
			out.add(end);
		}
		out.sort(Comparator.comparingLong((TimelineEvent e) -> e.at).thenComparingInt(e -> order(e.type)));
		return out;
	}

	private static TimelineEvent chatGroup(SessionRecord s, List<ChatRecord> chat, int from, int to) {
		TimelineEvent e = event(TimelineEvent.Type.CHAT, chat.get(from).at, s);
		e.endAt = chat.get(to - 1).at;
		e.chatFrom = from;
		e.chatTo = to;
		e.count = to - from;
		e.visitId = chat.get(from).visitId;
		e.serverKey = chat.get(from).serverKey;
		int system = 0;
		for (int i = from; i < to; i++) {
			if (chat.get(i).isSystem()) {
				system++;
			}
		}
		e.title = e.count == 1 ? "1 chat message" : e.count + " chat messages";
		e.detail = (e.count - system) + " player · " + system + " system";
		return e;
	}

	private static TimelineEvent event(TimelineEvent.Type type, long at, SessionRecord s) {
		TimelineEvent e = new TimelineEvent();
		e.type = type;
		e.at = at;
		e.sessionId = s.id;
		return e;
	}

	private static int order(TimelineEvent.Type t) {
		return switch (t) {
			case SESSION_START -> 0;
			case SERVER_JOIN, RECONNECTED -> 2;
			case SESSION_RECOVERED -> 8;
			case SESSION_END -> 9;
			default -> 5;
		};
	}

	private static boolean eq(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

	private static String nullTo(String s, String d) {
		return s == null ? d : s;
	}
}
