/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.fs.WorkspaceFileSystemService;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.oct.internal.rpc.BaseMessageHandler;
import org.eclipse.oct.internal.rpc.FileSystemMessageHandler;
import org.eclipse.oct.internal.rpc.FileSystemService;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.internal.rpc.ServiceProcess;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Wraps a real {@link ServiceProcess} (spawning the shipped native
 * {@code oct-service-process} executable) together with a
 * {@link TestOCTMessageHandler} and the production {@link FileSystemMessageHandler}
 * so tests can drive a peer end-to-end over the real OCT protocol.
 *
 * <p>For host peers a real {@link CollaborationInstance} is wired to a
 * {@link WorkspaceFileSystemService} backed by a temporary {@link IProject}, so
 * that inbound {@code fileSystem/*} RPCs from the guest are answered by the
 * production path-conversion code against real files on disk. For guest peers
 * we deliberately skip constructing the CollaborationInstance to avoid
 * triggering the linked-folder / workbench code paths (see
 * {@code CollaborationInstance.initializeSharedFolders} and
 * {@code EditorManager}), which are out of scope for this suite.
 */
public final class TestPeer implements AutoCloseable {

	public enum Role {
		HOST, GUEST
	}

	private final Role role;
	private final String username;
	private final EventEmitter<CollaborationInstance> onSessionCreated = new EventEmitter<>();
	private final TestOCTMessageHandler octHandler;
	private final FileSystemMessageHandler fsHandler;
	private final ServiceProcess serviceProcess;
	private final BaseMessageHandler.BaseRemoteInterface remoteProxy;

	private IProject project;
	private WorkspaceFileSystemService workspaceFs;
	private CollaborationInstance instance;
	private SessionData sessionData;

	public TestPeer(String serverUrl, String username, Role role) {
		this.role = role;
		this.username = username;
		this.octHandler = new TestOCTMessageHandler(serverUrl, onSessionCreated, username);
		this.fsHandler = new FileSystemMessageHandler(serverUrl, onSessionCreated);
		// Bypass AuthenticationService/Equinox secure storage: tests always perform
		// a fresh simple-login against the local server, so there is never a saved
		// token to look up, and secure storage can otherwise prompt for a master
		// password the first time it's touched.
		this.serviceProcess = new ServiceProcess(serverUrl, List.<BaseMessageHandler>of(fsHandler, octHandler),
				() -> null);
		this.remoteProxy = serviceProcess.getOctService();
	}

	// ---- Test configuration ----

	public String username() {
		return username;
	}

	public Role role() {
		return role;
	}

	public void setJoinPolicy(java.util.function.Function<
			org.eclipse.oct.internal.protocol.User, Boolean> policy) {
		octHandler.setJoinPolicy(policy);
	}

	// ---- Handshake ----

	/**
	 * Create a room. The {@link IProject} supplied here becomes the host's
	 * shared workspace; its content is what {@link WorkspaceFileSystemService}
	 * reads from when the guest issues {@code fileSystem/*} RPCs.
	 */
	public SessionData createRoom(IProject hostProject, Workspace workspace) throws Exception {
		if (role != Role.HOST) {
			throw new IllegalStateException("createRoom() is only valid for HOST peers");
		}
		this.project = hostProject;
		this.workspaceFs = new WorkspaceFileSystemService(hostProject);
		this.sessionData = octService().createRoom(workspace).get(30, TimeUnit.SECONDS);
		wireCollaborationInstance();
		return sessionData;
	}

	public SessionData joinRoom(String roomId) throws Exception {
		if (role != Role.GUEST) {
			throw new IllegalStateException("joinRoom() is only valid for GUEST peers");
		}
		this.sessionData = octService().joinRoom(roomId).get(30, TimeUnit.SECONDS);
		return sessionData;
	}

	/** Block until the service process delivers the {@code init} notification. */
	public InitData awaitInit(long timeout, TimeUnit unit) throws Exception {
		return octHandler.initFuture().get(timeout, unit);
	}

	/** Host peer id, learned from the {@code init} notification. */
	public String hostPeerId() {
		try {
			return awaitInit(30, TimeUnit.SECONDS).host.id;
		} catch (TimeoutException e) {
			throw new IllegalStateException("Never received init notification for peer '" + username + "'", e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ---- Accessors ----

	public OCTService octService() {
		return (OCTService) remoteProxy;
	}

	public FileSystemService fileSystemService() {
		return (FileSystemService) remoteProxy;
	}

	public SessionData sessionData() {
		return sessionData;
	}

	public CollaborationInstance collaborationInstance() {
		return instance;
	}

	public Peer selfPeer() {
		return octHandler.latestPeer();
	}

	public CompletableFuture<TestOCTMessageHandler.TextSelectionEvent> firstTextSelection() {
		return octHandler.firstTextSelection();
	}

	public CompletableFuture<TestOCTMessageHandler.DocumentUpdateEvent> firstDocumentUpdate() {
		return octHandler.firstDocumentUpdate();
	}

	public CompletableFuture<String> firstEditorOpened() {
		return octHandler.firstEditorOpened();
	}

	// ---- Wiring ----

	private void wireCollaborationInstance() {
		this.instance = new CollaborationInstance(remoteProxy, project, sessionData, /* isHost */ true);
		this.instance.setWorkspaceFileSystem(workspaceFs);
		onSessionCreated.fire(instance);
	}

	// ---- Lifecycle ----

	@Override
	public void close() {
		try {
			if (serviceProcess != null) {
				serviceProcess.close();
			}
		} catch (Exception ignored) {
		}
		if (instance != null) {
			try {
				instance.dispose();
			} catch (Exception ignored) {
			}
			instance = null;
		}
		// Project deletion is left to the test / EclipseTestProjects so callers can
		// inspect the workspace state after the peer is closed if needed.
		project = null;
	}
}
