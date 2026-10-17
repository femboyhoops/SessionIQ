package com.sessioniq.analytics;

import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.ChatArchive;
import com.sessioniq.track.ServerIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChatSearch {
	public static final class Criteria {
		public String text = "";
		public String sender = "";
		public Set<String> servers = new HashSet<>();
		public long fromMillis = Long.MIN_VALUE;
		public long toMillis = Long.MAX_VALUE;
		public String sessionId;
		public Set<String> categories = new HashSet<>();
		public boolean newestFirst = true;
		public boolean mergePorts;

		public Criteria copy() {
			Criteria c = new Criteria();
			c.text = text;
			c.sender = sender;
			c.servers = new HashSet<>(servers);
			c.fromMillis = fromMillis;
			c.toMillis = toMillis;
			c.sessionId = sessionId;
			c.categories = new HashSet<>(categories);
			c.newestFirst = newestFirst;
			c.mergePorts = mergePorts;
			return c;
		}
	}

	public record Page(List<ChatRecord> items, boolean exhausted, int damagedLines, List<String> errors) {
	}

	private final ChatArchive archive;
	private final Criteria criteria;
	private final String textLower;
	private final String senderLower;
	private final List<SessionRecord> sessions;
	private int sessionIndex;
	private List<Path> partitions;
	private int partitionIndex;
	private List<ChatRecord> current;
	private int recordIndex;
	private boolean exhausted;

	public ChatSearch(ChatArchive archive, List<SessionRecord> snapshot, Criteria criteria) {
		this.archive = archive;
		this.criteria = criteria;
		this.textLower = criteria.text == null ? "" : criteria.text.trim().toLowerCase(Locale.ROOT);
		this.senderLower = criteria.sender == null ? "" : criteria.sender.trim().toLowerCase(Locale.ROOT);
		List<SessionRecord> candidates = new ArrayList<>();
		for (SessionRecord s : snapshot) {
			if (couldMatch(s)) {
				candidates.add(s);
			}
		}
		candidates.sort((a, b) -> Long.compare(a.startedAt, b.startedAt));
		if (criteria.newestFirst) {
			Collections.reverse(candidates);
		}
		this.sessions = candidates;
	}

	private boolean couldMatch(SessionRecord s) {
		if (s.chatCount == 0 && !s.isActive()) {
			return false;
		}
		if (criteria.sessionId != null && !criteria.sessionId.equals(s.id)) {
			return false;
		}
		long end = s.isActive() ? Long.MAX_VALUE : s.endEstimate() + 60_000;
		if (end < criteria.fromMillis || s.startedAt >= criteria.toMillis) {
			return false;
		}
		if (!criteria.servers.isEmpty()) {
			for (VisitRecord v : s.visits) {
				if (criteria.servers.contains(ServerIdentity.groupKey(v, criteria.mergePorts))) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	public boolean matches(ChatRecord r) {
		if (r.at < criteria.fromMillis || r.at >= criteria.toMillis) {
			return false;
		}
		if (!criteria.categories.isEmpty() && !criteria.categories.contains(r.category)) {
			return false;
		}
		if (!criteria.servers.isEmpty() && !criteria.servers.contains(ServerIdentity.groupKey(r.serverKey, criteria.mergePorts))) {
			return false;
		}
		if (!senderLower.isEmpty() && (r.sender == null || !r.sender.toLowerCase(Locale.ROOT).contains(senderLower))) {
			return false;
		}
		return textLower.isEmpty() || r.text.toLowerCase(Locale.ROOT).contains(textLower);
	}

	public Page next(int size) {
		List<ChatRecord> out = new ArrayList<>(size);
		int damaged = 0;
		List<String> errors = new ArrayList<>();
		while (out.size() < size && !exhausted) {
			if (current == null || recordIndex >= current.size()) {
				if (!advancePartition(errors)) {
					exhausted = true;
					break;
				}
				damaged += lastDamaged;
				continue;
			}
			ChatRecord r = current.get(recordIndex++);
			if (matches(r)) {
				out.add(r);
			}
		}
		return new Page(out, exhausted, damaged, errors);
	}

	private int lastDamaged;

	private boolean advancePartition(List<String> errors) {
		lastDamaged = 0;
		while (true) {
			if (partitions != null && partitionIndex < partitions.size()) {
				ChatArchive.PartitionData data = ChatArchive.readPartition(partitions.get(partitionIndex++));
				lastDamaged = data.damagedLines();
				if (data.error() != null) {
					errors.add(data.error());
				}
				current = new ArrayList<>(data.records());
				current.sort((a, b) -> Long.compare(a.at, b.at));
				if (criteria.newestFirst) {
					Collections.reverse(current);
				}
				recordIndex = 0;
				return true;
			}
			if (sessionIndex >= sessions.size()) {
				return false;
			}
			SessionRecord s = sessions.get(sessionIndex++);
			partitions = new ArrayList<>(archive.partitions(s.id));
			if (criteria.newestFirst) {
				Collections.reverse(partitions);
			}
			partitionIndex = 0;
		}
	}

	public boolean isExhausted() {
		return exhausted;
	}
}
