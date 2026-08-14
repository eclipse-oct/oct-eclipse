/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.oct.internal.editor.EditorManager;
import org.eclipse.oct.internal.fs.OctFileSystem;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.FileChange;
import org.eclipse.oct.internal.protocol.FileChangeEvent;
import org.eclipse.oct.internal.protocol.FileChangeEventType;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.BaseMessageHandler;
import org.eclipse.oct.internal.rpc.FileSystemService;
import org.eclipse.oct.internal.util.EventEmitter;
import org.eclipse.oct.internal.util.OctPaths;
import org.eclipse.swt.widgets.Display;

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

	/**
	 * Peer id of an in-flight inbound {@code fileSystem/writeFile}, keyed by
	 * protocol path. Used so a guest save is not echoed back to that same guest
	 * as another writeFile (VS Code would {@code document.save()} again).
	 */
	private final Map<String, String> inboundWriteOrigins = new ConcurrentHashMap<>();
	private final Map<String, long[]> lastPropagatedSave = new ConcurrentHashMap<>();

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

	public void setIdentity(Peer peer) {
		this.identity = peer;
		onPeersChanged.fire(null);
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
		if (editorManager != null) {
			editorManager.forgetPeer(peer.id);
		}
		onPeersChanged.fire(null);
	}

	/** Display name for a peer id (cursor name tag / session view). */
	public String peerDisplayName(String peerId) {
		if (peerId == null) {
			return "";
		}
		if (identity != null && peerId.equals(identity.id) && identity.name != null) {
			return identity.name;
		}
		if (host != null && peerId.equals(host.id) && host.name != null) {
			return host.name;
		}
		for (Peer guest : guests) {
			if (peerId.equals(guest.id) && guest.name != null && !guest.name.isBlank()) {
				return guest.name;
			}
		}
		return peerId;
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
		if (editorManager != null) {
			editorManager.recordPeerDocument(peerId, documentPath);
			if (isHost) {
				editorManager.guestOpenedEditor(documentPath);
			}
		}
	}

	public void beginInboundWrite(String path, String origin) {
		if (path != null && origin != null && !origin.isBlank() && !"broadcast".equals(origin)) {
			inboundWriteOrigins.put(OctPaths.normalize(path), origin);
		}
	}

	public void endInboundWrite(String path) {
		if (path != null) {
			inboundWriteOrigins.remove(OctPaths.normalize(path));
		}
	}

	/**
	 * Host save (or a guest save that landed on disk) must also
	 * {@code fileSystem/writeFile} each other guest. VS Code only calls
	 * {@code document.save()} on {@code fs.onWriteFile} — {@code fs.onChange}
	 * only refreshes the explorer, so a host Ctrl+S otherwise leaves the guest
	 * editor dirty.
	 */
	public void propagateSaveToGuests(String protocolPath, byte[] content) {
		if (!isHost || content == null || guests.isEmpty() || !(remoteInterface instanceof FileSystemService fs)) {
			return;
		}
		String path = OctPaths.normalize(protocolPath);
		if (path.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		int hash = Arrays.hashCode(content);
		long[] prev = lastPropagatedSave.get(path);
		if (prev != null && prev[0] == hash && now - prev[1] < 750) {
			return;
		}
		lastPropagatedSave.put(path, new long[] { hash, now });

		String exclude = inboundWriteOrigins.get(path);
		FileContent payload = new FileContent(content);
		for (Peer guest : guests) {
			if (guest == null || guest.id == null || guest.id.equals(exclude)) {
				continue;
			}
			try {
				fs.writeFile(path, payload, guest.id);
			} catch (Exception e) {
				LOG.warning("Failed to propagate save of '" + path + "' to " + guest.id + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Host persisted {@code protocolPath}. Mark an open guest editor clean.
	 * Do not {@code refreshLocal} an open editor — Eclipse would reload from
	 * EFS and {@code DocumentSyncListener} would send the whole buffer back
	 * as an insert, duplicating content. Runs off the JSON-RPC reader.
	 */
	public void acceptHostSave(String protocolPath, byte[] content) {
		if (isHost || protocolPath == null) {
			return;
		}
		invalidateGuestCache(protocolPath);
		Display.getDefault().syncExec(() -> {
			boolean open = editorManager != null && editorManager.saveIfOpen(protocolPath, content);
			if (!open) {
				refreshGuestPath(protocolPath);
			}
		});
	}

	public void handleFileSystemChange(FileChangeEvent event) {
		if (isHost) {
			return;
		}
		// Must not saveIfOpen on the JSON-RPC reader thread: the UI save may
		// stat the oct:// store and would deadlock the same connection.
		Display.getDefault().asyncExec(() -> {
			boolean needProjectRefresh = false;
			for (FileChange change : event.changes) {
				invalidateGuestCache(change.path);
				if (change.type == FileChangeEventType.Update && editorManager != null
						&& editorManager.saveIfOpen(change.path, null)) {
					continue;
				}
				if (!refreshGuestPath(change.path)) {
					needProjectRefresh = true;
				}
			}
			if (needProjectRefresh) {
				try {
					project.refreshLocal(IResource.DEPTH_INFINITE, null);
				} catch (CoreException e) {
					LOG.warning("Failed to refresh project: " + e.getMessage());
				}
			}
		});
	}

	private void invalidateGuestCache(String protocolPath) {
		OctFileSystem efs = OctFileSystem.getInstance();
		if (efs == null || protocolPath == null) {
			return;
		}
		try {
			efs.invalidate(OctPaths.toOctUri(sessionData.roomId, protocolPath));
		} catch (Exception ignored) {
		}
	}

	private boolean refreshGuestPath(String protocolPath) {
		if (protocolPath == null) {
			return false;
		}
		IResource resource = project.findMember(new Path(OctPaths.normalize(protocolPath)));
		if (resource == null || !resource.exists()) {
			return false;
		}
		try {
			resource.refreshLocal(IResource.DEPTH_ZERO, null);
			return true;
		} catch (CoreException e) {
			LOG.warning("Failed to refresh '" + protocolPath + "': " + e.getMessage());
			return false;
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
						URI octUri = OctPaths.toOctUri(sessionId, root);
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
	private EditorManager editorManager;

	public void setEditorManager(EditorManager em) {
		this.editorManager = em;
	}

	public EditorManager getEditorManager() {
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
