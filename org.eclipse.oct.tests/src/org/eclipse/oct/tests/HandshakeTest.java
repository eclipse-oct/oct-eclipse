/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * End-to-end tests of the OCT handshake: host {@code createRoom} / guest
 * {@code joinRoom} / host {@code joinSessionRequest} / guest {@code init}.
 *
 * <p>Uses the real Node.js OCT server (via {@link OctTestServer}) and the real
 * native {@code oct-service-process} executable that the plugin ships, so the
 * exact same wire protocol described in
 * {@code open-collaboration-protocol/src/messages.ts} is exercised.
 */
@TestInstance(Lifecycle.PER_CLASS)
class HandshakeTest {

	private String serverUrl;
	private final List<TestPeer> openPeers = new ArrayList<>();
	private final List<IProject> openProjects = new ArrayList<>();

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@AfterEach
	void closePeers() {
		for (TestPeer p : openPeers) {
			try {
				p.close();
			} catch (Exception ignored) {
			}
		}
		openPeers.clear();
		for (IProject project : openProjects) {
			EclipseTestProjects.deleteProject(project);
		}
		openProjects.clear();
	}

	// OctTestServer is closed via its own JVM shutdown hook.

	// ---------------------------------------------------------------

	@Test
	@DisplayName("host and guest complete the full handshake (create, join, joinSessionRequest, init)")
	void hostAndGuestCompleteFullHandshake() throws Exception {
		IProject hostProject = createProject("handshake-host");

		TestPeer host = newHost();
		TestPeer guest = newGuest();

		Workspace ws = new Workspace(hostProject.getName(), new String[] { "testFolder" });
		SessionData hostSession = host.createRoom(hostProject, ws);
		assertNotNull(hostSession, "host createRoom returned null SessionData");
		assertNotNull(hostSession.roomId, "roomId must be set on host session");
		assertNotNull(hostSession.workspace, "workspace echoed back on host session");
		assertEquals(ws.name, hostSession.workspace.name);
		assertEquals(ws.folders[0], hostSession.workspace.folders[0]);

		SessionData guestSession = guest.joinRoom(hostSession.roomId);
		assertNotNull(guestSession, "guest joinRoom returned null SessionData");
		assertEquals(hostSession.roomId, guestSession.roomId, "guest and host must agree on roomId");
		assertNotNull(guestSession.workspace, "workspace metadata is sent to guest");
		assertEquals(ws.name, guestSession.workspace.name, "workspace name is preserved");
		assertEquals(ws.folders[0], guestSession.workspace.folders[0], "shared root folder name is preserved");

		// Guest must receive an init notification with the host peer info and the
		// same workspace we advertised at createRoom time.
		InitData init = guest.awaitInit(30, TimeUnit.SECONDS);
		assertNotNull(init, "guest never received init");
		assertNotNull(init.protocol, "init.protocol must include the OCT protocol version");
		assertNotNull(init.host, "init.host must be set for the guest");
		assertNotNull(init.host.id, "init.host.id must be set");
		assertNotNull(init.workspace, "init.workspace must be set");
		assertEquals(ws.name, init.workspace.name);
		assertEquals(ws.folders[0], init.workspace.folders[0]);
	}

	@Test
	@DisplayName("host rejecting joinSessionRequest fails the guest's joinRoom future")
	void hostRejectingJoinFailsGuestJoin() throws Exception {
		IProject hostProject = createProject("handshake-reject-host");
		TestPeer host = newHost();
		AtomicBoolean policyCalled = new AtomicBoolean();
		host.setJoinPolicy(user -> {
			policyCalled.set(true);
			return false;
		});

		TestPeer guest = newGuest();

		SessionData hostSession = host.createRoom(hostProject,
				new Workspace(hostProject.getName(), new String[] { "testFolder" }));

		Exception ex = assertThrows(Exception.class,
				() -> guest.joinRoom(hostSession.roomId),
				"guest joinRoom must fail when the host rejects the join request");
		assertNotNull(ex, "join failure must propagate an exception");
		assertTrue(policyCalled.get(), "host's join policy must have been consulted");
		// And init must never arrive on a rejected join.
		assertThrows(TimeoutException.class,
				() -> guest.awaitInit(2, TimeUnit.SECONDS),
				"init must not arrive to a rejected guest");
	}

	@Test
	@DisplayName("second guest joining an existing room also completes the handshake")
	void secondGuestJoinsExistingRoom() throws Exception {
		IProject hostProject = createProject("handshake-multi-host");
		TestPeer host = newHost();
		TestPeer guestA = newGuest("guestA");
		TestPeer guestB = newGuest("guestB");

		Workspace ws = new Workspace(hostProject.getName(), new String[] { "testFolder" });
		SessionData hostSession = host.createRoom(hostProject, ws);

		SessionData sessionA = guestA.joinRoom(hostSession.roomId);
		InitData initA = guestA.awaitInit(30, TimeUnit.SECONDS);

		SessionData sessionB = guestB.joinRoom(hostSession.roomId);
		InitData initB = guestB.awaitInit(30, TimeUnit.SECONDS);

		assertEquals(hostSession.roomId, sessionA.roomId);
		assertEquals(hostSession.roomId, sessionB.roomId);
		assertEquals(initA.host.id, initB.host.id, "both guests must see the same host peer id");
		assertNotNull(initB.guests, "guests array should be populated for the second joiner");
	}

	@Test
	@DisplayName("joining a non-existent room fails")
	void joinNonExistentRoomFails() {
		TestPeer guest = newGuest();
		Exception ex = assertThrows(Exception.class,
				() -> guest.joinRoom("this-room-does-not-exist"),
				"joining a bogus room id must fail");
		assertNotNull(ex);
	}

	// ---------------------------------------------------------------

	private TestPeer newHost() {
		TestPeer peer = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		openPeers.add(peer);
		return peer;
	}

	private TestPeer newGuest() {
		return newGuest("guest");
	}

	private TestPeer newGuest(String username) {
		TestPeer peer = new TestPeer(serverUrl, username, TestPeer.Role.GUEST);
		openPeers.add(peer);
		return peer;
	}

	private IProject createProject(String name) throws Exception {
		IProject p = EclipseTestProjects.createProject(name);
		openProjects.add(p);
		return p;
	}
}
