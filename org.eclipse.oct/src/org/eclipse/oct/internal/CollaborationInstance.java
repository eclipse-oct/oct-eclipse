/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.FileChange;
import org.eclipse.oct.internal.protocol.FileChangeEvent;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.BaseMessageHandler;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Per-session state holder.
 */
public class CollaborationInstance {

	private static final Logger LOG = Logger.getLogger(CollaborationInstance.class.getName());

	public final BaseMessageHandler.BaseRemoteInterface remoteInterface;
	public final IProject project;
	public final SessionData sessionData;
	public final boolean isHost;

	public final List<Peer> guests = new ArrayList<>();
	public Peer host;
	public Peer identity;

	public final PeerColors peerColors = new PeerColors();
	public final EventEmitter<Void> onPeersChanged = new EventEmitter<>();

	private WorkspaceFileSystemServiceHolder workspaceFileSystemHolder;

	public CollaborationInstance(BaseMessageHandler.BaseRemoteInterface remoteInterface, IProject project,
			SessionData sessionData, boolean isHost) {
		this.remoteInterface = remoteInterface;
		this.project = project;
		this.sessionData = sessionData;
		this.isHost = isHost;
		LOG.info("Initialized collaboration instance for project: " + project.getName());
	}

	public void setWorkspaceFileSystem(WorkspaceFileSystemServiceHolder wfs) {
		this.workspaceFileSystemHolder = wfs;
	}

	public WorkspaceFileSystemServiceHolder getWorkspaceFileSystem() {
		return workspaceFileSystemHolder;
	}

	public void initPeers(InitData initData) {
		if (initData.guests != null) {
			for (Peer g : initData.guests) {
				guests.add(g);
			}
		}
		host = initData.host;
		if (!isHost) {
			initializeSharedFolders();
		}
		onPeersChanged.fire(null);
	}

	public void peerJoined(Peer peer) {
		guests.add(peer);
		onPeersChanged.fire(null);
	}

	public void peerLeft(Peer peer) {
		guests.removeIf(g -> g.id.equals(peer.id));
		onPeersChanged.fire(null);
	}

	public void updateTextSelection(String url, ClientTextSelection[] selections) {
		// Delegate to EditorManager (wired at session start)
		if (editorManager != null) {
			editorManager.updateTextSelection(url, selections);
		}
	}

	public void updateDocument(String url, TextDocumentInsert[] updates) {
		if (editorManager != null) {
			editorManager.updateDocument(url, updates);
		}
	}

	public void editorOpened(String documentPath, String peerId) {
		if (isHost && editorManager != null) {
			editorManager.guestOpenedEditor(documentPath);
		}
	}

	public void handleFileSystemChange(FileChangeEvent event) {
		if (!isHost) {
			// Invalidate EFS caches and refresh the project resource tree
			org.eclipse.oct.internal.fs.OctFileSystem efs = org.eclipse.oct.internal.fs.OctFileSystem.getInstance();
			for (FileChange change : event.changes) {
				if (efs != null) {
					try {
						URI uri = new URI("oct", sessionData.roomId, "/" + change.path, null, null);
						efs.invalidate(uri);
					} catch (Exception ignored) {
					}
				}
			}
			org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
				try {
					project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
				} catch (org.eclipse.core.runtime.CoreException e) {
					LOG.warning("Failed to refresh project: " + e.getMessage());
				}
			});
		}
	}

	private void initializeSharedFolders() {
		String sessionId = sessionData.roomId;
		if (sessionData.workspace == null || sessionData.workspace.folders == null) {
			return;
		}

		// Run off the JSON-RPC reader thread: creating the links triggers
		// synchronous readDir/stat round trips to the host over the same service
		// process connection, which would deadlock the reader thread.
		Job job = new Job("Initializing OCT shared folders") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				for (String root : sessionData.workspace.folders) {
					try {
						// Create a linked folder pointing at oct://<sessionId>/<root>
						URI octUri = new URI("oct", sessionId, "/" + root, null, null);
						IFolder folder = project.getFolder(root);
						if (!folder.exists()) {
							folder.createLink(octUri, IResource.REPLACE | IResource.ALLOW_MISSING_LOCAL, monitor);
						}
						// Populate the linked folder's children from the host. Without an
						// initial refresh the workspace model stays empty and nothing is
						// shown in the Project Explorer until a file change arrives.
						folder.refreshLocal(IResource.DEPTH_INFINITE, monitor);
					} catch (Exception e) {
						LOG.warning("Failed to create linked folder for root '" + root + "': " + e.getMessage());
					}
				}
				LOG.info("Initialized " + sessionData.workspace.folders.length + " shared folder(s) for session: "
						+ sessionId);
				return Status.OK_STATUS;
			}
		};
		job.schedule();
	}

	// EditorManager reference set after construction to avoid circular deps
	private org.eclipse.oct.internal.editor.EditorManager editorManager;

	public void setEditorManager(org.eclipse.oct.internal.editor.EditorManager em) {
		this.editorManager = em;
	}

	public org.eclipse.oct.internal.editor.EditorManager getEditorManager() {
		return editorManager;
	}

	public void dispose() {
		LOG.info("Disposing collaboration instance for project: " + project.getName());
		if (editorManager != null) {
			// Tear down the workbench part listener and all per-editor document/
			// selection listeners. Otherwise they outlive the session and keep
			// sending to the (now destroyed) service process, causing
			// "Stream closed" errors.
			editorManager.dispose();
			editorManager = null;
		}
	}
}
