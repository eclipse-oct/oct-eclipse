/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.protocol.FileContent;
import org.eclipse.oct.protocol.Peer;
import org.eclipse.oct.protocol.SessionData;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestFileSystemMessageHandler.WriteFileEvent;
import org.eclipse.oct.tests.support.TestPeer;
import org.eclipse.oct.util.OctPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * Host Ctrl+S must {@code fileSystem/writeFile} each guest. VS Code only
 * {@code document.save()}s on writeFile; {@code fileSystem/change} only
 * refreshes the explorer. See {@code CollaborationInstance.propagateSaveToGuests}.
 */
@TestInstance(Lifecycle.PER_CLASS)
class HostSavePropagationTest {

	private static final String ORIGINAL = "original host content";
	private static final String SAVED = "saved by eclipse host";

	private String serverUrl;
	private TestPeer host;
	private TestPeer guest;
	private IProject hostProject;
	private IFile hostFile;
	private String octPath;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("save-prop-host");
		hostFile = EclipseTestProjects.writeFile(hostProject, "test.txt", ORIGINAL);
		octPath = OctPaths.fromHostResource(hostFile);

		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		guest = new TestPeer(serverUrl, "guest", TestPeer.Role.GUEST);

		SessionData hostSession = host.createRoom(hostProject,
				new Workspace(hostProject.getName(), new String[] { hostProject.getName() }));
		guest.joinRoom(hostSession.roomId);
		guest.awaitInit(30, TimeUnit.SECONDS);
		awaitGuestOnHost(host);

		SessionService sessions = SessionService.getInstance();
		assertNotNull(sessions, "Activator must have installed SessionService (WorkspaceChangeListener looks it up)");
		sessions.registerInstance(hostProject, host.collaborationInstance());
	}

	@AfterEach
	void closeSession() {
		if (hostProject != null && SessionService.getInstance() != null) {
			SessionService.getInstance().unregisterInstance(hostProject);
		}
		if (guest != null) {
			guest.close();
		}
		if (host != null) {
			host.close();
		}
		EclipseTestProjects.deleteProject(hostProject);
	}

	@Test
	@DisplayName("saving a file on the Eclipse host sends fileSystem/writeFile to the guest")
	void hostSaveSendsWriteFileToGuest() throws Exception {
		assertTrue(guest.writeFiles().isEmpty(), "guest must not have seen a writeFile before the host save");

		hostFile.setContents(new ByteArrayInputStream(SAVED.getBytes(StandardCharsets.UTF_8)), true, false,
				new NullProgressMonitor());

		WriteFileEvent received = guest.firstWriteFile().get(15, TimeUnit.SECONDS);
		assertNotNull(received, "guest must receive fileSystem/writeFile when the host saves");
		assertTrue(OctPaths.referToSameDocument(octPath, received.path()),
				"writeFile path must be the protocol path of the saved file (got '" + received.path() + "', expected '"
						+ octPath + "')");
		assertArrayEquals(SAVED.getBytes(StandardCharsets.UTF_8), received.content(),
				"writeFile payload must be the content the host just saved");
	}

	@Test
	@DisplayName("a guest-originated writeFile is not echoed back to that same guest")
	void guestSaveIsNotEchoedBackToSameGuest() throws Exception {
		String hostId = guest.hostPeerId();
		guest.fileSystemService().writeFile(octPath, new FileContent(SAVED.getBytes(StandardCharsets.UTF_8)), hostId)
				.get(15, TimeUnit.SECONDS);

		// Give the host time to persist and (wrongly) echo if the exclude path is broken.
		Thread.sleep(1000);
		assertTrue(guest.writeFiles().isEmpty(),
				"the guest that sent writeFile must not receive its own save back (got "
						+ guest.writeFiles().size() + ")");
	}

	private static void awaitGuestOnHost(TestPeer host) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (host.collaborationInstance() == null || host.collaborationInstance().guests.isEmpty()) {
			if (System.nanoTime() > deadline) {
				throw new TimeoutException("Host never recorded a joined guest (peerJoined missing)");
			}
			Thread.sleep(50);
		}
		Peer guest = host.collaborationInstance().guests.get(0);
		assertNotNull(guest.id, "joined guest must have a peer id");
	}
}
