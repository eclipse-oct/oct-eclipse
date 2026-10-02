/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.auth.AuthenticationService;
import org.eclipse.oct.protocol.AuthMetadata;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.InitData;
import org.eclipse.oct.protocol.Peer;
import org.eclipse.oct.protocol.TextDocumentInsert;
import org.eclipse.oct.protocol.User;
import org.eclipse.oct.util.EventEmitter;
import org.eclipse.oct.util.UIThread;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

/**
 * Handles inbound OCT messages from the service process.
 *
 */
public class OCTMessageHandler extends BaseMessageHandler {

	private static final Logger LOG = Logger.getLogger(OCTMessageHandler.class.getName());

	public OCTMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated) {
		super(serverUrl, onSessionCreated);
	}

	@Override
	public Class<? extends BaseRemoteInterface> getRemoteInterface() {
		return OCTService.class;
	}

	// ---- Inbound notifications from service process ----

	@JsonNotification
	public void authentication(String token, AuthMetadata metadata) {
		AuthenticationService.getInstance().authenticate(serverUrl, token, metadata);
	}

	@JsonNotification
	public void error(String message, String stack) {
		LOG.severe("OCT service error: " + message);
		if (stack != null) {
			LOG.severe("Stack: " + stack);
		}
	}

	@JsonRequest(value = "room/joinSessionRequest")
	public CompletableFuture<Boolean> joinSessionRequest(User user) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Display display = UIThread.display();
		if (display == null) {
			// Decline rather than drop the request: the workbench is gone, so
			// the dialog can never be shown, and an uncompleted future leaves
			// the guest waiting for a response forever.
			result.complete(false);
			return result;
		}
		display.asyncExec(() -> {
			// A guest join request can arrive while no window has focus (e.g. the
			// host alt-tabbed away). If getActiveWorkbenchWindow() NPEs here without
			// this try/finally, result never completes and the guest hangs forever
			// waiting for a response that will never come.
			try {
				String displayName = (user.email != null && !user.email.isEmpty()) ? user.name + " (" + user.email + ")"
						: user.name;
				boolean accepted = MessageDialog.openQuestion(activeShellOrNull(), "Join Request",
						displayName + " via " + user.authProvider + " wants to join the collaboration session. Accept?");
				result.complete(accepted);
			} finally {
				result.complete(false);
			}
		});
		return result;
	}

	private static Shell activeShellOrNull() {
		try {
			return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
		} catch (Exception e) {
			Display display = UIThread.display();
			return display != null ? display.getActiveShell() : null;
		}
	}

	@JsonNotification
	public void init(InitData initData) {
		if (collaborationInstance != null) {
			collaborationInstance.initPeers(initData);
		} else {
			executeOnSetInstance.add(() -> collaborationInstance.initPeers(initData));
		}
	}

	@JsonNotification
	public void peerInfo(Peer peer) {
		if (collaborationInstance != null) {
			collaborationInstance.setIdentity(peer);
		} else {
			executeOnSetInstance.add(() -> collaborationInstance.setIdentity(peer));
		}
	}

	@JsonNotification
	public void peerJoined(Peer peer) {
		if (collaborationInstance != null) {
			collaborationInstance.peerJoined(peer);
		}
	}

	@JsonNotification
	public void peerLeft(Peer peer) {
		if (collaborationInstance != null) {
			collaborationInstance.peerLeft(peer);
		}
	}

	@JsonNotification(value = "awareness/updateTextSelection")
	public void updateTextSelection(String url, ClientTextSelection[] selections) {
		if (collaborationInstance != null) {
			collaborationInstance.updateTextSelection(url, selections);
		}
	}

	@JsonNotification(value = "awareness/updateDocument")
	public void updateDocument(String url, TextDocumentInsert[] updates) {
		if (collaborationInstance != null) {
			collaborationInstance.updateDocument(url, updates);
		}
	}

	@JsonNotification
	public void editorOpened(String documentPath, String peerId) {
		if (collaborationInstance != null) {
			collaborationInstance.editorOpened(documentPath, peerId);
		}
	}

	@JsonNotification
	public void sessionClosed() {
		if (collaborationInstance != null && !collaborationInstance.isHost) {
			// Scheduled straight off the JSON-RPC reader: the deletion itself
			// runs in the WorkspaceJob, so hopping onto the UI thread first
			// bought nothing and only tied this cleanup to a display that is
			// already gone when the guest closes Eclipse (see UIThread).
			var project = collaborationInstance.project;
			WorkspaceJob job = new WorkspaceJob("Deleting Project: " + project.getName()) {
				@Override
				public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
					if (project != null && project.exists()) {
						project.delete(true, true, monitor);
					}
					return Status.OK_STATUS;
				}
			};
			job.setRule(project);
			job.setUser(false);
			job.schedule();
		}
	}

}
