/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;

import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.tests.support.TestOCTMessageHandler.DocumentUpdateEvent;

/**
 * Small polling helpers for waiting out the asynchronous seed-sync of a
 * peer's Yjs document replica — see {@code EditorManager
 * .confirmSeedThenEnableUpdates} and the extensive comment in
 * {@code AwarenessSmokeTest.documentUpdatesReachHost} for why this is needed:
 * {@code awareness/openDocument} is fire-and-forget, and a peer's own replica
 * only catches up once the seed round-trips through the OCT server. Sending
 * an offset-based edit before that lands races an empty/stale local Yjs
 * replica (Yjs silently clamps out-of-range offsets instead of rejecting
 * them), which is exactly what used to make these tests flaky.
 */
public final class TestSync {

	private static final long DEFAULT_TIMEOUT_SECONDS = 15;
	private static final long POLL_INTERVAL_MS = 50;

	private TestSync() {
	}

	/**
	 * Polls {@code awareness/getDocumentContent} on {@code peer} until it
	 * reports {@code expected}, or fails after {@link #DEFAULT_TIMEOUT_SECONDS}.
	 */
	public static void awaitDocumentContent(TestPeer peer, String path, String expected) throws Exception {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS);
		String last = null;
		do {
			FileContent content = peer.octService().getDocumentContent(path).get(5, TimeUnit.SECONDS);
			last = (content != null && content.content != null)
					? new String(content.content, StandardCharsets.UTF_8)
					: null;
			if (expected.equals(last)) {
				return;
			}
			Thread.sleep(POLL_INTERVAL_MS);
		} while (System.nanoTime() < deadlineNanos);
		fail("Document replica for '" + path + "' never synced to '" + expected + "' (last seen: '" + last + "')");
	}

	/**
	 * Polls {@code peer.documentUpdates()} until one matches {@code predicate},
	 * or fails after {@link #DEFAULT_TIMEOUT_SECONDS}. Unlike {@code
	 * peer.firstDocumentUpdate()}, this looks across every update received so
	 * far, not just the first — a peer already watching a path (e.g. via an
	 * earlier openDocument/updateDocument call) can receive several updates
	 * (a content seed, then live edits) before the one under test.
	 */
	public static DocumentUpdateEvent awaitDocumentUpdate(TestPeer peer, Predicate<DocumentUpdateEvent> predicate)
			throws Exception {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS);
		do {
			List<DocumentUpdateEvent> updates = peer.documentUpdates();
			for (DocumentUpdateEvent event : updates) {
				if (predicate.test(event)) {
					return event;
				}
			}
			Thread.sleep(POLL_INTERVAL_MS);
		} while (System.nanoTime() < deadlineNanos);
		fail("No awareness/updateDocument matching the given predicate arrived within " + DEFAULT_TIMEOUT_SECONDS
				+ "s (received " + peer.documentUpdates().size() + " update(s) total)");
		throw new AssertionError("unreachable");
	}
}
