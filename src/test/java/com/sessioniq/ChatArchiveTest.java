package com.sessioniq;

import com.sessioniq.analytics.ChatSearch;
import com.sessioniq.model.ChatCategory;
import com.sessioniq.model.ChatRecord;
import com.sessioniq.model.SessionRecord;
import com.sessioniq.model.VisitRecord;
import com.sessioniq.storage.ChatArchive;
import com.sessioniq.storage.DataPaths;
import com.sessioniq.storage.StorageService;
import com.sessioniq.util.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatArchiveTest {
	@TempDir
	Path dir;
	DataPaths paths;
	StorageService storage;
	ChatArchive chat;
	SessionRecord session;
	VisitRecord a;
	VisitRecord b;

	@BeforeEach
	void setUp() {
		paths = new DataPaths(dir);
		storage = new StorageService();
		chat = new ChatArchive(paths, storage);
		long t0 = 1_760_000_000_000L;
		session = TestData.session(t0, 3_600_000);
		a = TestData.visit(session, "a.example.com", 25565, t0 + 1000, 1_000_000);
		b = TestData.visit(session, "b.example.com", 25565, t0 + 2_000_000, 1_000_000);
	}

	@AfterEach
	void tearDown() {
		storage.shutdown(2000);
	}

	private ChatRecord msg(VisitRecord v, long at, String sender, String text, ChatCategory cat) {
		ChatRecord r = new ChatRecord();
		r.id = Ids.chat();
		r.at = at;
		r.sessionId = session.id;
		r.visitId = v.id;
		r.serverKey = v.serverKey;
		r.sender = sender;
		r.text = text;
		r.category = cat.id;
		r.origin = cat == ChatCategory.SYSTEM ? ChatRecord.ORIGIN_SYSTEM : ChatRecord.ORIGIN_PLAYER;
		return r;
	}

	@Test
	void largeArchiveIsPartitionedAndFullySearchable() {
		int n = 12_345;
		for (int i = 0; i < n; i++) {
			VisitRecord v = i < n / 2 ? a : b;
			chat.append(msg(v, session.startedAt + 1000 + i, i % 2 == 0 ? "Alice" : "Bob", "message number " + i + (i % 1000 == 0 ? " diamonds" : ""),
				i % 10 == 0 ? ChatCategory.SYSTEM : ChatCategory.CHAT));
		}
		chat.flush();
		assertTrue(storage.flush(10_000));
		List<Path> parts = chat.partitions(session.id);
		assertEquals(3, parts.size(), "5000-line partitions");
		session.chatCount = n;

		ChatSearch.Criteria all = new ChatSearch.Criteria();
		ChatSearch search = new ChatSearch(chat, List.of(session), all);
		int total = 0;
		long previous = Long.MAX_VALUE;
		while (!search.isExhausted()) {
			for (ChatRecord r : search.next(500).items()) {
				assertTrue(r.at <= previous, "newest first");
				previous = r.at;
				total++;
			}
		}
		assertEquals(n, total);

		ChatSearch.Criteria text = new ChatSearch.Criteria();
		text.text = "DIAMONDS";
		assertEquals(13, drain(new ChatSearch(chat, List.of(session), text)).size(), "case-insensitive text search");

		ChatSearch.Criteria sender = new ChatSearch.Criteria();
		sender.sender = "bob";
		sender.categories.add(ChatCategory.CHAT.id);
		List<ChatRecord> bob = drain(new ChatSearch(chat, List.of(session), sender));
		assertTrue(bob.stream().allMatch(r -> "Bob".equals(r.sender) && !r.isSystem()));

		ChatSearch.Criteria server = new ChatSearch.Criteria();
		server.servers.add(b.serverKey);
		server.newestFirst = false;
		List<ChatRecord> onB = drain(new ChatSearch(chat, List.of(session), server));
		assertEquals(n - n / 2, onB.size());
		assertTrue(onB.getFirst().at < onB.getLast().at, "oldest first");

		ChatSearch.Criteria window = new ChatSearch.Criteria();
		window.fromMillis = session.startedAt + 1000 + 100;
		window.toMillis = session.startedAt + 1000 + 200;
		assertEquals(100, drain(new ChatSearch(chat, List.of(session), window)).size(), "date range is inclusive-exclusive");
	}

	private static List<ChatRecord> drain(ChatSearch s) {
		List<ChatRecord> out = new ArrayList<>();
		while (!s.isExhausted()) {
			out.addAll(s.next(1000).items());
		}
		return out;
	}

	@Test
	void damagedLinesAreSkippedAndCounted() throws Exception {
		chat.append(msg(a, session.startedAt + 5, "Alice", "first", ChatCategory.CHAT));
		chat.flush();
		storage.flush(5000);
		Path part = chat.partitions(session.id).getFirst();
		Files.writeString(part, "{\"id\":\"broken\",\"t\":12", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		chat.append(msg(a, session.startedAt + 6, "Alice", "after crash", ChatCategory.CHAT));
		chat.flush();
		storage.flush(5000);
		ChatArchive.PartitionData data = ChatArchive.readPartition(part);
		assertEquals(2, data.records().size());
		assertEquals(1, data.damagedLines());
	}

	@Test
	void deletingMessagesRewritesOnlyMatches() throws Exception {
		List<ChatRecord> written = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			ChatRecord r = msg(a, session.startedAt + i, "Alice", "m" + i, ChatCategory.CHAT);
			written.add(r);
			chat.append(r);
		}
		List<ChatRecord> removed = chat.deleteMessages(Map.of(session.id, Set.of(written.get(3).id, written.get(7).id))).get();
		assertEquals(2, removed.size());
		List<ChatRecord> left = chat.readSession(session.id);
		assertEquals(18, left.size());
		assertTrue(left.stream().noneMatch(r -> r.id.equals(written.get(3).id)));
	}

	@Test
	void unicodeIsStoredAsUtf8() throws Exception {
		chat.append(msg(a, session.startedAt + 1, "Zoë", "héllo — 你好 ✓", ChatCategory.CHAT));
		chat.flush();
		storage.flush(5000);
		String raw = Files.readString(chat.partitions(session.id).getFirst(), StandardCharsets.UTF_8);
		assertTrue(raw.contains("你好"));
		assertEquals("héllo — 你好 ✓", chat.readSession(session.id).getFirst().text);
	}
}
