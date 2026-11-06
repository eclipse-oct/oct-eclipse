/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.oct.internal.fs.OctFileStore;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.TextDocumentInsert;
import org.eclipse.oct.ui.PeerColors;
import org.eclipse.oct.util.EventEmitter;
import org.eclipse.oct.util.OctPaths;
import org.eclipse.oct.util.UIThread;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Tracks open editors and syncs their documents and selections with peers.
 * Per-editor wiring lives in {@link TrackedEditor}, peer cursor/selection
 * drawing in {@link PeerAnnotationRenderer}; this class owns the path->editor
 * map, the seed gate and follow-mode.
 */
public class EditorManager implements IPartListener2 {

	private static final Logger LOG = Logger.getLogger(EditorManager.class.getName());

	private final OCTService remoteService;
	private final IProject project;
	private final PeerAnnotationRenderer annotations;
	private final DocumentSeedGate seedGate;

	/**
	 * Per editor-path state. Concurrent because {@link DocumentSeedGate} polls
	 * {@link #findEditorState} from a background thread while the UI thread
	 * mutates the map in {@link #registerEditor}/{@link #unregisterEditor} — a racy
	 * {@code null} there used to leave {@code DocumentSyncListener.pending}
	 * permanently unflushed (edits only ever reaching peers via a later save).
	 */
	private final Map<String, TrackedEditor> editorStates = new ConcurrentHashMap<>();

	private final AtomicBoolean disposed = new AtomicBoolean(false);

	/**
	 * Volatile because follow state is no longer only toggled from the Session
	 * view on the UI thread: auto-follow arms it from the JSON-RPC reader when
	 * {@code init} names the host, while {@code updateTextSelection} and the
	 * view read it on the UI thread.
	 */
	private volatile String followingPeerId = null;

	/** Last known document path per peer (from selection or editorOpened). */
	private final Map<String, String> peerDocumentPaths = new ConcurrentHashMap<>();
	public final EventEmitter<Void> onPresenceChanged = new EventEmitter<>();

	/**
	 * When {@code true}, a guest opening/selecting a file steals the host's editor
	 * focus (auto-opens and activates it). Off by default since this is disruptive
	 * to the host's own workflow.
	 */
	private volatile boolean followGuestSelection = false;
	private boolean isHost;

	public EditorManager(OCTService remoteService, IProject project, boolean isHost, PeerColors peerColors) {
		this.remoteService = remoteService;
		this.project = project;
		this.annotations = new PeerAnnotationRenderer(peerColors);
		this.seedGate = new DocumentSeedGate(remoteService, this::findEditorState);
		this.isHost = isHost;
		registerPartListener();
	}

	/**
	 * Resolves a peer id to the display name shown on the cursor name tag. Defaults
	 * to the id itself when unset (tests / peers not yet in init).
	 */
	public void setPeerNameLookup(Function<String, String> peerNameLookup) {
		annotations.setPeerNameLookup(peerNameLookup);
	}

	private void registerPartListener() {
		UIThread.syncExec(() -> {
			if (!PlatformUI.isWorkbenchRunning()) {
				return;
			}
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			if (page != null) {
				page.addPartListener(this);
			}
			adoptOpenEditors();
		});
	}

	/**
	 * Registers editors that were already open when the session started.
	 * {@link IPartListener2#partOpened} only ever fires for editors opened
	 * <em>after</em> this listener is attached, and switching to an already-open
	 * tab fires {@code partActivated}, which bails when there is no
	 * {@link TrackedEditor} yet. Without this sweep a file the user had open before
	 * hosting/joining stays completely unsynced for the whole session: no
	 * {@link DocumentSyncListener}, no {@link SelectionSyncListener}, no peer
	 * annotations, and no {@code awareness/openDocument} seed — so peer cursors
	 * never appear, local typing is never broadcast, incoming edits are dropped by
	 * {@link #updateDocument}, and content only ever converges via a save.
	 *
	 * <p>
	 * Mirrors the reference clients: VS Code's
	 * {@code vscode.workspace.textDocuments.forEach(d => registerTextDocument(d))}
	 * and IntelliJ's replay of {@code FileEditorManager.allEditors}.
	 *
	 * <p>
	 * Must run on the UI thread (callers: {@link #registerPartListener}).
	 */
	private void adoptOpenEditors() {
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference ref : page.getEditorReferences()) {
					// restore=true: a tab restored from a previous workbench session is
					// not instantiated until touched, and would otherwise be skipped.
					if (ref.getEditor(true) instanceof ITextEditor textEditor) {
						trackEditor(textEditor);
					}
				}
			}
		}
	}

	// ---- IPartListener2 ----

	@Override
	public void partOpened(IWorkbenchPartReference ref) {
		if (disposed.get() || !(ref instanceof IEditorReference editorRef)) {
			return;
		}
		// Use the opened editor, not the active one — otherwise opening a second
		// file while another stays active seeds the wrong (or empty) Yjs doc.
		if (editorRef.getEditor(false) instanceof ITextEditor textEditor) {
			trackEditor(textEditor);
		}
	}

	/**
	 * Registers {@code textEditor} for sync and seeds the shared document with its
	 * content. Shared by {@link #partOpened} (editors opened during the session)
	 * and {@link #adoptOpenEditors} (editors already open when it started).
	 */
	private void trackEditor(ITextEditor textEditor) {
		if (disposed.get()) {
			return;
		}
		IFile file = textEditor.getEditorInput().getAdapter(IFile.class);
		if (file == null || !file.exists() || !file.getProject().equals(project)) {
			// ignore files from other projects
			return;
		}
		String octPath = octPath(file);
		boolean firstRegistration = registerEditor(octPath, textEditor);
		if (!firstRegistration) {
			// Already tracked — nothing to register or seed a second time.
			return;
		}
		// Opening a brand-new editor doesn't itself fire a selection-changed event —
		// SelectionSyncListener (just attached by registerEditor above) only reacts
		// to *future* caret moves. Without sending the current selection now, a peer
		// with followingPeerId pointed at us (or just watching the "File" column)
		// never learns we opened this document until we happen to move the caret,
		// mirroring VS Code's onDidChangeActiveTextEditor handling (see partActivated).
		sendCurrentSelection(octPath, textEditor);
		seedGate.seedFromEditor(octPath, textEditor, file);
	}

	private String octPath(IFile file) {
		return OctPaths.fromEditorFile(file, isHost);
	}

	/**
	 * Resolve an incoming protocol path to an {@link IFile}. Host: workspace root
	 * ({@code sharedRoot} is the project name). Guest: temp project
	 * ({@code sharedRoot} is a linked folder). Returns {@code null} if the resource
	 * does not exist — callers must not NPE on that.
	 */
	private IFile eclipseFile(String octPath) {
		String normalized = OctPaths.normalize(octPath);
		if (normalized.isEmpty()) {
			return null;
		}
		IFile iFile = isHost ? project.getWorkspace().getRoot().getFile(new Path(normalized))
				: project.getFile(new Path(normalized));
		return iFile.exists() ? iFile : null;
	}

	@Override
	public void partClosed(IWorkbenchPartReference ref) {
		if (!(ref instanceof IEditorReference editorRef)) {
			return;
		}
		IEditorPart editor = editorRef.getEditor(false);
		if (!(editor instanceof ITextEditor)) {
			return;
		}
		IFile file = editor.getEditorInput().getAdapter(IFile.class);
		if (file == null) {
			return;
		}
		unregisterEditor(octPath(file));
	}

	/**
	 * Switching to an already-open tab doesn't move the caret, so no
	 * selection-changed event fires and {@link SelectionSyncListener} never
	 * sends anything — peers (e.g. a host with {@link #followPeer} active for
	 * this guest) would never learn the active document changed. Resend the
	 * current selection unconditionally on activation instead, mirroring the
	 * VS Code client's {@code onDidChangeActiveTextEditor} handling.
	 */
	@Override
	public void partActivated(IWorkbenchPartReference ref) {
		if (disposed.get() || !(ref instanceof IEditorReference editorRef)) {
			return;
		}
		IEditorPart editor = editorRef.getEditor(false);
		if (!(editor instanceof ITextEditor textEditor)) {
			return;
		}
		IFile file = editor.getEditorInput().getAdapter(IFile.class);
		if (file == null || !file.exists() || !file.getProject().equals(project)) {
			return;
		}
		String octPath = octPath(file);
		if (findEditorState(octPath) == null) {
			// Not registered yet — partOpened() sends the initial selection itself.
			return;
		}
		sendCurrentSelection(octPath, textEditor);
	}

	/**
	 * Sends {@code textEditor}'s current caret/selection immediately. Needed both
	 * from {@link #partOpened} (a freshly attached {@link SelectionSyncListener}
	 * only reacts to future caret moves, never the initial position) and from
	 * {@link #partActivated} (switching to an already-open tab doesn't move the
	 * caret, so no selection-changed event fires either) — without this, a peer
	 * following us via {@link #followingPeerId} never learns we switched to a new
	 * document until we happen to move the caret.
	 */
	private void sendCurrentSelection(String octPath, ITextEditor textEditor) {
		if (textEditor.getSelectionProvider() == null) {
			return;
		}
		if (!(textEditor.getSelectionProvider().getSelection() instanceof ITextSelection textSelection)) {
			return;
		}
		ClientTextSelection selection = new ClientTextSelection("self", textSelection.getOffset(),
				textSelection.getOffset() + textSelection.getLength(), false);
		try {
			remoteService.updateTextSelection(octPath, new ClientTextSelection[] { selection });
		} catch (Exception e) {
			LOG.warning("Failed to send selection for " + octPath + ": " + e.getMessage());
		}
	}

	@Override
	public void partDeactivated(IWorkbenchPartReference ref) {
	}

	@Override
	public void partBroughtToTop(IWorkbenchPartReference ref) {
	}

	@Override
	public void partHidden(IWorkbenchPartReference ref) {
	}

	@Override
	public void partVisible(IWorkbenchPartReference ref) {
	}

	@Override
	public void partInputChanged(IWorkbenchPartReference ref) {
	}

	// ---- Editor registration ----

	/**
	 * Registers listeners/annotations for {@code editor} if not already tracked.
	 * Returns {@code true} only if this call performed the (one-time) registration,
	 * so callers can gate actions that must happen exactly once per document.
	 */
	private boolean registerEditor(String octPath, ITextEditor editor) {
		if (findEditorState(octPath) != null) {
			return false;
		}
		TrackedEditor tracked = TrackedEditor.attach(octPath, editor, remoteService);
		if (tracked == null) {
			return false;
		}
		editorStates.put(octPath, tracked);
		return true;
	}

	private void unregisterEditor(String octPath) {
		TrackedEditor state = findEditorState(octPath);
		if (state == null) {
			return;
		}
		state.detach();
		editorStates.values().remove(state);
		annotations.forget(state);
	}

	// ---- Remote updates ----

	/**
	 * Apply remote document edits with echo suppression. Port of
	 * EditorManager.updateDocument.
	 */
	public void updateDocument(String octPath, TextDocumentInsert[] updates) {
		if (updates == null || updates.length == 0) {
			return;
		}
		UIThread.asyncExec(() -> {
			TrackedEditor state = findEditorState(octPath);
			if (state == null) {
				return;
			}

			state.sendUpdates().set(false);
			DocumentRewriteSession session = null;
			IDocument doc = state.document();
			try {
				if (doc instanceof IDocumentExtension4 ext4) {
					session = ext4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED);
				}
				for (TextDocumentInsert insert : updates) {
					int start = insert.startOffset;
					int end = insert.endOffset != null ? insert.endOffset : start;
					int length = end - start;
					String text = insert.text != null ? insert.text.replace("\r\n", "\n") : "";
					int docLen = doc.getLength();
					start = Math.min(start, docLen);
					length = Math.min(length, docLen - start);
					if (isSeedEcho(doc, start, length, text)) {
						continue;
					}
					try {
						doc.replace(start, length, text);
					} catch (BadLocationException e) {
						LOG.warning("Bad location applying remote edit: " + e.getMessage());
					}
				}
			} finally {
				if (session != null && doc instanceof IDocumentExtension4 ext4) {
					ext4.stopRewriteSession(session);
				}
				state.sendUpdates().set(true);
			}
		});
	}

	/**
	 * Guest editors load from EFS before Yjs is seeded. The seed then arrives as
	 * insert-at-0 of the <em>entire</em> current buffer. Applying that prepends and
	 * duplicates. A one-character insert at 0 (typing at the start) must still go
	 * through — {@code "2"} into {@code "1"} is a real edit.
	 */
	public static boolean isSeedEcho(IDocument doc, int start, int length, String text) {
		if (length != 0 || start != 0 || text == null || text.length() <= 1) {
			return false;
		}
		return text.equals(doc.get().replace("\r\n", "\n"));
	}

	/**
	 * Update peer cursor/selection annotations. Port of
	 * EditorManager.updateTextSelection.
	 */
	public void updateTextSelection(String octPath, ClientTextSelection[] selections) {
		UIThread.asyncExec(() -> {
			// Record each peer's current document (for the Session View "File" column)
			// and react to follow-mode *before* checking for a local TrackedEditor —
			// otherwise both only ever worked for paths we already happened to have
			// open ourselves, which defeats the point of a presence display and of
			// following a peer into a file we haven't opened yet. followTo() opens
			// the file itself when needed, so no local editor state is required here.
			Set<String> reportingPeers = new HashSet<>();
			if (selections != null) {
				for (ClientTextSelection sel : selections) {
					if (sel.peer == null) {
						continue;
					}
					reportingPeers.add(sel.peer);
					recordPeerDocument(sel.peer, octPath);
					if (sel.peer.equals(followingPeerId)) {
						followTo(eclipseFile(octPath), sel.start);
					}
				}
			}

			TrackedEditor state = findEditorState(octPath);
			if (state != null) {
				annotations.render(state, selections);
			}

			// A reporting peer's selections just replaced (or, if state was null,
			// simply confirmed) its presence in octPath. No empty update is ever sent
			// for a path a peer just left (see EditorManager class doc / the exec
			// plan this closes) — so without this sweep, its annotations from a
			// *previously* open file would linger there forever.
			if (!reportingPeers.isEmpty()) {
				for (Map.Entry<String, TrackedEditor> entry : editorStates.entrySet()) {
					if (OctPaths.referToSameDocument(entry.getKey(), octPath)) {
						continue;
					}
					for (String peerId : reportingPeers) {
						annotations.removePeer(entry.getValue(), peerId);
					}
				}
			}
		});
	}

	/**
	 * Called on the host when a guest opens an editor. Regardless of the "follow
	 * guest selection" setting, the Yjs doc must be seeded with real content or the
	 * guest ends up looking at a blank buffer. Only actually stealing the host's
	 * editor focus (opening/activating the part) is gated behind
	 * {@link #followGuestSelection}.
	 *
	 * <p>
	 * When the host already has the file open, the registered editor has seeded it
	 * (and holds the authoritative, possibly unsaved, buffer) — nothing to do here.
	 * Otherwise push the on-disk content ourselves, exactly once per path. Upstream
	 * {@code editor.onOpen} does read the host's file via {@code readOwnFile} as a
	 * backstop, but only when the path is not in the shared doc yet, and it never
	 * tells the service process what a host <em>editor</em> holds.
	 */
	public void guestOpenedEditor(String documentPath) {
		UIThread.asyncExec(() -> {
			String path = OctPaths.normalize(documentPath);
			IFile file = eclipseFile(path);
			if (file == null) {
				return;
			}

			boolean alreadyKnown = findEditorState(path) != null || !seedGate.markSeeded(path);

			if (followGuestSelection) {
				// Opens the part, which triggers partOpened() -> trackEditor(), which
				// seeds from the live buffer and drops the seeded-path marker again.
				activateEditor(file);
			} else if (!alreadyKnown) {
				seedGate.seedFromDisk(file, path);
			}
		});
	}

	private void activateEditor(IFile file) {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			IDE.openEditor(page, file, resolveEditorId(file), true);
		} catch (Exception e) {
			LOG.warning("Failed to open editor for guest: " + e.getMessage());
		}
	}

	/**
	 * {@code oct://} files only exist in the shared session, not on the
	 * guest's real filesystem, so an extension with no registered Eclipse
	 * editor must not fall through to the registry's external/system-editor
	 * default - that editor couldn't resolve the file at all.
	 */
	private static String resolveEditorId(IFile file) {
		IEditorRegistry registry = PlatformUI.getWorkbench().getEditorRegistry();
		IEditorDescriptor descriptor = registry.getDefaultEditor(file.getName());
		if (descriptor == null || !descriptor.isInternal()) {
			return EditorsUI.DEFAULT_TEXT_EDITOR_ID;
		}
		return descriptor.getId();
	}

	/**
	 * Opens an editor for the on-disk file behind a protocol path, e.g. the
	 * file a peer currently has open as shown in the File column of
	 * {@link org.eclipse.oct.ui.SessionView}. No-op if the path doesn't
	 * resolve to an existing local resource.
	 */
	public void openWithEditor(String octPath) {
		IFile file = eclipseFile(octPath);
		if (file != null) {
			activateEditor(file);
		}
	}

	public boolean isFollowGuestSelection() {
		return followGuestSelection;
	}

	public void setFollowGuestSelection(boolean followGuestSelection) {
		if (this.followGuestSelection == followGuestSelection) {
			return;
		}
		this.followGuestSelection = followGuestSelection;
		onPresenceChanged.fire(null);
	}

	private void followTo(IFile file, int offset) {
		try {
			if (file == null || !file.exists()) {
				return;
			}
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			// activate=true: bring the tab to the front (and (re)open it if the
			// user had closed it) instead of just updating a background editor —
			// otherwise the follower never actually sees the tab switch.
			IEditorPart editor = IDE.openEditor(page, file, resolveEditorId(file), true);
			if (editor instanceof ITextEditor textEditor) {
				// Clamp against the just-opened editor's own document rather than
				// relying on a pre-existing local TrackedEditor (there may be none yet
				// if we're following into a file we haven't opened before).
				IDocument doc = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
				int clamped = doc != null ? Math.max(0, Math.min(offset, doc.getLength())) : Math.max(0, offset);
				// Scroll the viewport to the peer's location without touching our
				// own caret/selection — selectAndReveal() would move *our* cursor
				// there too, which is surprising and would itself broadcast a new
				// selection update. ISourceViewer#revealRange only scrolls.
				ISourceViewer viewer = TrackedEditor.sourceViewerOf(textEditor);
				if (viewer != null) {
					viewer.revealRange(clamped, 0);
				} else {
					// Fallback for editors we can't get a source viewer from.
					textEditor.selectAndReveal(clamped, 0);
				}
			}
		} catch (Exception e) {
			LOG.warning("Follow-mode failed: " + e.getMessage());
		}
	}

	public void followPeer(String peerId) {
		this.followingPeerId = peerId;
		setFollowGuestSelection(true);
		onPresenceChanged.fire(null);
	}

	public void stopFollowing() {
		this.followingPeerId = null;
		setFollowGuestSelection(false);
		onPresenceChanged.fire(null);
	}

	public void recordPeerDocument(String peerId, String octPath) {
		if (peerId == null || octPath == null || octPath.isBlank()) {
			return;
		}
		String path = OctPaths.normalize(octPath);
		String prev = peerDocumentPaths.put(peerId, path);
		if (!path.equals(prev)) {
			onPresenceChanged.fire(null);
		}
	}

	public String getPeerDocumentPath(String peerId) {
		return peerId == null ? null : peerDocumentPaths.get(peerId);
	}

	/**
	 * A peer left the session. Clears its presence tracking, resets follow state
	 * if it was the peer being followed, and sweeps its {@link PeerAnnotation}s
	 * from every open editor — {@code peerLeft} never arrives with a
	 * corresponding empty {@code awareness/updateTextSelection}, so nothing else
	 * would ever remove its ghost cursor/selection.
	 */
	public void forgetPeer(String peerId) {
		if (peerId == null) {
			return;
		}
		peerDocumentPaths.remove(peerId);
		if (peerId.equals(followingPeerId)) {
			stopFollowing();
		}
		UIThread.asyncExec(() -> {
			for (TrackedEditor state : editorStates.values()) {
				annotations.removePeer(state, peerId);
			}
		});
	}

	public String getFollowingPeerId() {
		return this.followingPeerId;
	}

	/**
	 * Save an open editor for {@code path} so its dirty state is cleared. Used when
	 * a peer persists the file (guest->host or host->guest {@code writeFile}).
	 * Returns {@code true} if handled by an open editor.
	 */
	public boolean saveIfOpen(String path, byte[] content) {
		if (findEditorState(path) == null) {
			return false;
		}
		AtomicBoolean handled = new AtomicBoolean(false);
		UIThread.syncExec(() -> {
			TrackedEditor state = findEditorState(path);
			if (state == null) {
				return;
			}
			// The document is normally already up to date via Yjs sync; only
			// replace it if the incoming content actually differs, suppressing
			// echo so we don't broadcast the change back to peers.
			if (content != null) {
				String incoming = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
				if (!incoming.equals(state.document().get())) {
					state.sendUpdates().set(false);
					try {
						state.document().set(incoming);
					} finally {
						state.sendUpdates().set(true);
					}
				}
			}
			try {
				if (isHost) {
					state.editor().doSave(new NullProgressMonitor());
				} else {
					acceptRemoteSave(state);
				}
				handled.set(true);
			} catch (Exception e) {
				LOG.warning("Failed to save editor for: " + path + " - " + e.getMessage());
			}
		});
		return handled.get();
	}

	/**
	 * Guest-side: the host already persisted the file. Mark this editor clean
	 * without {@code doSave()} — that uses overwrite=false and would prompt "file
	 * has changed on the file system" after {@code refreshLocal} updates the oct://
	 * stamp, and would also echo {@code writeFile} back to the host.
	 */
	private void acceptRemoteSave(TrackedEditor state) throws CoreException {
		IDocumentProvider provider = state.editor().getDocumentProvider();
		IEditorInput input = state.editor().getEditorInput();
		if (provider == null || input == null || !provider.canSaveDocument(input)) {
			return;
		}
		state.sendUpdates().set(false);
		try {
			OctFileStore.suppressWrite(() -> {
				try {
					provider.saveDocument(new NullProgressMonitor(), input, state.document(), true);
				} catch (CoreException e) {
					throw new RuntimeException(e);
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof CoreException core) {
				throw core;
			}
			throw e;
		} finally {
			state.sendUpdates().set(true);
		}
	}

	private TrackedEditor findEditorState(String octPath) {
		if (octPath == null) {
			return null;
		}
		TrackedEditor exact = editorStates.get(OctPaths.normalize(octPath));
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, TrackedEditor> entry : editorStates.entrySet()) {
			if (OctPaths.referToSameDocument(entry.getKey(), octPath)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public void dispose() {
		disposed.set(true);
		// Synchronously, like disposed above: an already-armed name-tag timer must
		// see the session as gone when it fires, not only once the block below ran.
		annotations.dispose();
		seedGate.dispose();
		peerDocumentPaths.clear();
		followingPeerId = null;
		followGuestSelection = false;
		UIThread.asyncExec(() -> {
			if (PlatformUI.isWorkbenchRunning()) {
				try {
					IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
					if (page != null) {
						page.removePartListener(this);
					}
				} catch (Exception ignored) {
				}
			}
			// Unregister editors on the UI thread: removing selection listeners,
			// annotation models and drawing strategies touches SWT/viewer APIs.
			new ArrayList<>(editorStates.keySet()).forEach(this::unregisterEditor);
		});
	}
}
