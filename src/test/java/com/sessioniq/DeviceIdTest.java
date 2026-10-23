package com.sessioniq;

import com.sessioniq.online.DeviceId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The device file location is redirected into build/ for tests (see build.gradle). */
class DeviceIdTest {
	@Test
	void sameIdForEveryInstanceUntilForgotten() throws Exception {
		DeviceId.forget();
		String first = DeviceId.load("fallback");
		assertTrue(first.matches("[0-9a-f-]{36}"), first);
		assertTrue(Files.exists(DeviceId.file()));
		assertEquals(first, DeviceId.load("fallback"), "a second instance reads the same ID");

		DeviceId.forget();
		assertFalse(Files.exists(DeviceId.file()), "Delete my online data removes the file");
		assertNotEquals(first, DeviceId.load("fallback"), "a new random ID is made only when used again");
	}

	@Test
	void damagedFileIsReplaced() throws Exception {
		Files.createDirectories(DeviceId.file().getParent());
		Files.writeString(DeviceId.file(), "not json");
		assertTrue(DeviceId.load("fallback").matches("[0-9a-f-]{36}"));
	}
}
