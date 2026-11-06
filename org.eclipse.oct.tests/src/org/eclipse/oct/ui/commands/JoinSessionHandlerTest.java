/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class JoinSessionHandlerTest {

	@Test
	void bareRoomIdIsValid() {
		assertNull(JoinSessionHandler.validateRoomToken("abc123"));
	}

	@Test
	void shareUrlIsValid() {
		assertNull(JoinSessionHandler.validateRoomToken("https://oct.example.com#abc123"));
	}

	@Test
	void nullInputIsRejected() {
		assertEquals("Room ID cannot be empty", JoinSessionHandler.validateRoomToken(null));
	}

	@Test
	void blankInputIsRejected() {
		assertEquals("Room ID cannot be empty", JoinSessionHandler.validateRoomToken("   "));
	}

	@Test
	void pastedFilesystemPathIsRejected() {
		assertEquals("Room ID can only contain letters and digits", JoinSessionHandler
				.validateRoomToken("/Users/dhuebner/Entwicklung/eclipse-oct/runtime-EclipseApplication-guest/"));
	}

	@Test
	void nonAlphanumericRoomIdIsRejected() {
		assertEquals("Room ID can only contain letters and digits", JoinSessionHandler.validateRoomToken("abc-123"));
	}

	@Test
	void localServerShareUrlIsValid() {
		assertNull(JoinSessionHandler.validateRoomToken("http://127.0.0.1:8100#r21YykHVKdbQjrSzVB50e7uY"));
	}

	@Test
	void shareUrlWithSlashInFragmentIsRejected() {
		assertEquals("Room ID in the URL can only contain letters and digits",
				JoinSessionHandler.validateRoomToken("http://127.0.0.1:8100#/adasd"));
	}

	@Test
	void urlWithoutFragmentIsRejected() {
		assertEquals("URL must include a room id after '#'",
				JoinSessionHandler.validateRoomToken("http://127.0.0.1:8100"));
	}

	@Test
	void unparsableUrlIsRejected() {
		assertEquals("Not a valid URL", JoinSessionHandler.validateRoomToken("http://a b c://#roomid"));
	}
}
