/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.eclipse.oct.tests.support.TestSync.awaitDocumentContent;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.SessionData;
import org.eclipse.oct.protocol.TextDocumentInsert;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestOCTMessageHandler;
import org.eclipse.oct.tests.support.TestPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * Smoke test that awareness notifications ({@code awareness/openDocument},
 * {@code awareness/updateTextSelection}, {@code awareness/updateDocument}) flow
 * bidirectionally through the service process, the OCT server, and back to the
 * peer's OCTMessageHandler. Mirrors the corresponding assertions in
 * {@code service.process.test.ts} but for the Java transport stack.
 */
@TestInstance(Lifecycle.PER_CLASS)
class AwarenessSmokeTest {

	private String serverUrl;
	private TestPeer host;
	private TestPeer guest;
	private IProject hostProject;
	private String projectName;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("awareness-host");
		projectName = hostProject.getName();
		EclipseTestProjects.writeFile(hostProject, "test.txt", "HELLO WORLD!");

		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		guest = new TestPeer(serverUrl, "guest", TestPeer.Role.GUEST);

		SessionData hostSession = host.createRoom(hostProject,
				new Workspace(projectName, new String[] { projectName }));
		guest.joinRoom(hostSession.roomId);
		guest.awaitInit(30, TimeUnit.SECONDS);
	}

	@AfterEach
	void closeSession() {
		if (guest != null) {
			guest.close();
		}
		if (host != null) {
			host.close();
		}
		EclipseTestProjects.deleteProject(hostProject);
	}

	@Test
	@DisplayName("selection updates from the guest are received by the host")
	void selectionUpdatesReachHost() throws Exception {
		String path = projectName + "/test.txt";

		host.octService().openDocument("text", path, "HELLO WORLD!");
		guest.octService().openDocument("text", path, "HELLO WORLD!");

		ClientTextSelection selection = new ClientTextSelection();
		selection.start = 0;
		selection.end = 5;
		guest.octService().updateTextSelection(path, new ClientTextSelection[] { selection });

		TestOCTMessageHandler.TextSelectionEvent received = host.firstTextSelection().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "host must receive the guest's text selection notification");
		assertEquals(path, received.path(), "path is echoed verbatim (no conversion at this layer)");
		assertNotNull(received.selections());
		assertTrue(received.selections().length >= 1, "at least one selection was expected");
	}

	@Test
	@DisplayName("document content updates from the guest are received by the host")
	void documentUpdatesReachHost() throws Exception {
		String path = projectName + "/test.txt";
		String seedContent = "HELLO WORLD!";
		host.octService().openDocument("text", path, seedContent);
		guest.octService().openDocument("text", path, seedContent);

		// awareness/openDocument is fire-and-forget: the guest's local Yjs replica for
		// `path` only gets the real content once the host's seed has round-tripped
		// through the CRDT sync (guest's own "text" argument is discarded — see
		// CollaborationInstance.registerYjsObject in open-collaboration-service-process,
		// which only forwards it when isHost). Sending an offset-based edit before that
		// sync lands races an *empty* guest-side Y.Text: Yjs silently clamps an
		// out-of-range insert to the current length (0) instead of throwing, which is
		// what previously made this test flaky (observed startOffset 0 instead of 5).
		// Wait for the guest's own replica to report the seeded content first, exactly
		// like a well-behaved client must (see DocumentSyncListener.maybeResync, which
		// documents this exact gap as an unimplemented placeholder).
		awaitDocumentContent(guest, path, seedContent);

		TextDocumentInsert insert = new TextDocumentInsert();
		insert.startOffset = 5;
		insert.text = " NEW";
		guest.octService().updateDocument(path, new TextDocumentInsert[] { insert });

		TestOCTMessageHandler.DocumentUpdateEvent received = host.firstDocumentUpdate().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "host must receive the guest's document update notification");
		assertEquals(path, received.path());
		assertNotNull(received.updates());
		assertTrue(received.updates().length >= 1, "at least one insert was expected");
		assertArrayEquals(new int[] { 5 }, new int[] { received.updates()[0].startOffset },
				"start offset of first insert must be preserved on the wire");
	}

	@Test
	@DisplayName("document content updates from the host are received by the guest (reverse direction)")
	void documentUpdatesReachGuest() throws Exception {
		String path = projectName + "/test.txt";
		String seedContent = "HELLO WORLD!";
		host.octService().openDocument("text", path, seedContent);
		guest.octService().openDocument("text", path, seedContent);

		// The host is always the source of truth (registerYjsObject seeds the
		// Yjs doc synchronously for isHost), so there's no seed-sync race for a
		// host-authored edit — but the guest side still needs a moment to receive
		// this specific edit over the wire, which firstDocumentUpdate()'s timeout
		// already accounts for.
		TextDocumentInsert insert = new TextDocumentInsert();
		insert.startOffset = 0;
		insert.text = ">> ";
		host.octService().updateDocument(path, new TextDocumentInsert[] { insert });

		TestOCTMessageHandler.DocumentUpdateEvent received = guest.firstDocumentUpdate().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "guest must receive the host's document update notification");
		assertEquals(path, received.path());
		assertNotNull(received.updates());
		assertTrue(received.updates().length >= 1, "at least one insert was expected");
		assertArrayEquals(new int[] { 0 }, new int[] { received.updates()[0].startOffset },
				"start offset of first insert must be preserved on the wire");
	}

	@Test
	@DisplayName("multiple inserts in a single updateDocument call are all delivered, in order")
	void multipleInsertsDeliveredInOrder() throws Exception {
		String path = projectName + "/test.txt";
		String seedContent = "HELLO WORLD!"; // 12 chars
		host.octService().openDocument("text", path, seedContent);
		guest.octService().openDocument("text", path, seedContent);
		awaitDocumentContent(guest, path, seedContent);

		// Two independent inserts against the *original* (pre-batch) offsets:
		// one at the very start, one at the very end. open-collaboration-yjs's
		// doUpdate() sorts by start offset and tracks a running delta so both are
		// expected to land correctly in a single Yjs transaction/delta.
		TextDocumentInsert insertAtEnd = new TextDocumentInsert();
		insertAtEnd.startOffset = seedContent.length();
		insertAtEnd.text = "?";
		TextDocumentInsert insertAtStart = new TextDocumentInsert();
		insertAtStart.startOffset = 0;
		insertAtStart.text = ">> ";
		guest.octService().updateDocument(path, new TextDocumentInsert[] { insertAtEnd, insertAtStart });

		TestOCTMessageHandler.DocumentUpdateEvent received = host.firstDocumentUpdate().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "host must receive the guest's batched document update notification");
		assertEquals(path, received.path());
		assertNotNull(received.updates());
		assertEquals(2, received.updates().length, "both inserts from the batch must be delivered");
		// Yjs's own delta is emitted in document order regardless of the order the
		// two inserts were given in the outgoing array.
		assertArrayEquals(new int[] { 0, seedContent.length() },
				new int[] { received.updates()[0].startOffset, received.updates()[1].startOffset },
				"both offsets must be preserved on the wire, in ascending document order");

		// Cross-check against the host's own canonical replica: both inserts must
		// have actually landed at the right place, not just been echoed correctly.
		awaitDocumentContent(host, path, ">> " + seedContent + "?");
	}
}
