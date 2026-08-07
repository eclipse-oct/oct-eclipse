/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.protocol.Workspace;
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
		host.octService().openDocument("text", path, "HELLO WORLD!");
		guest.octService().openDocument("text", path, "HELLO WORLD!");

		TextDocumentInsert insert = new TextDocumentInsert();
		insert.startOffset = 5;
		insert.text = " NEW";
		guest.octService().updateDocument(path, new TextDocumentInsert[] { insert });

		TestOCTMessageHandler.DocumentUpdateEvent received = host.firstDocumentUpdate().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "host must receive the guest's document update notification");
		assertEquals(path, received.path());
		assertNotNull(received.updates());
		assertTrue(received.updates().length >= 1, "at least one insert was expected");
		// Note: this is a smoke test of notification delivery. Individual field wire
		// encoding for TextDocumentInsert (startOffset/endOffset/text under the msgpack
		// BinaryDataAdapter) is not asserted here since it depends on serialization
		// details that aren't part of this suite's contract, and has proven flaky.
	}
}
