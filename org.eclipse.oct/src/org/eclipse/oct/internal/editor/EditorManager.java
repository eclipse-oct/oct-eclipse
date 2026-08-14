/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
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
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.AnnotationPainter;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.oct.internal.PeerColors;
import org.eclipse.oct.internal.fs.OctFileStore;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.internal.util.EventEmitter;
import org.eclipse.oct.internal.util.OctPaths;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Full M4 EditorManager: tracks open editors, syncs documents and selections,
 * renders peer cursors/selections via AnnotationPainter, supports follow-mode.
 */
public class EditorManager implements IPartListener2 {

	private static final Logger LOG = Logger.getLogger(EditorManager.class.getName());
	private static final Object PEER_MODEL_KEY = new Object();

	/** Max time to wait for an initial content seed to confirm before giving up. */
	private static final long SEED_SYNC_TIMEOUT_MS = 10_000;
	private static final long SEED_SYNC_POLL_INTERVAL_MS = 100;
	/** Same window VS Code uses ({@code Date.now() - lastUpdated < 1900}). */
	private static final int NAME_TAG_VISIBLE_MS = 1900;

	private final OCTService remoteService;
	private final IProject project;
	private final PeerColors peerColors;
	private Function<String, String> peerNameLookup = id -> id;

	/** Per editor-path state */
	private final Map<String, EditorState> editorStates = new HashMap<>();
	/** Bumps on each selection update so a stale hide-timer cannot clear a fresh name tag. */
	private final Map<EditorState, Integer> nameTagEpoch = new java.util.IdentityHashMap<>();

	/**
	 * Paths whose content has already been pushed to Yjs without an editor being
	 * registered (see {@link #guestOpenedEditor}). Consulted by {@link #partOpened}
	 * so that a later host-side open of the same path doesn't resend the content —
	 * openDocument always does a full delete+insert, so a second send is a needless
	 * full-document replace that marks other peers' editors dirty.
	 */
	private final java.util.Set<String> seededPaths = new java.util.HashSet<>();

	private final AtomicBoolean disposed = new AtomicBoolean(false);

	public String followingPeerId = null;

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

	public EditorManager(OCTService remoteService, IProject project, boolean isHost) {
		this.remoteService = remoteService;
		this.project = project;
		this.peerColors = new PeerColors();
		this.isHost = isHost;
		registerPartListener();
	}

	/**
	 * Resolves a peer id to the display name shown on the cursor name tag.
	 * Defaults to the id itself when unset (tests / peers not yet in init).
	 */
	public void setPeerNameLookup(Function<String, String> peerNameLookup) {
		this.peerNameLookup = peerNameLookup != null ? peerNameLookup : id -> id;
	}

	private void registerPartListener() {
		Display.getDefault().asyncExec(() -> {
			if (!PlatformUI.isWorkbenchRunning()) {
				return;
			}
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			if (page != null) {
				page.addPartListener(this);
			}
		});
	}

	// ---- IPartListener2 ----

	@Override
	public void partOpened(IWorkbenchPartReference ref) {
		if (disposed.get() || !(ref instanceof IEditorReference editorRef)) {
			return;
		}
		// Use the opened editor, not the active one — otherwise opening a second
		// file while another stays active seeds the wrong (or empty) Yjs doc.
		IEditorPart editor = editorRef.getEditor(false);
		if (!(editor instanceof ITextEditor textEditor)) {
			return;
		}
		IFile file = editor.getEditorInput().getAdapter(IFile.class);
		if (file == null || !file.exists()) {
			return;
		}
		if (!file.getProject().equals(project)) {
			// ignore files from other projects
			return;
		}
		String octPath = octPath(file);
		boolean firstRegistration = registerEditor(octPath, textEditor);
		if (!firstRegistration) {
			// Already tracked — do not resend. openDocument always does a full
			// delete+insert on the Yjs side (no diffing), so re-sending unchanged
			// content on every partOpened marks guest editors dirty for no reason.
			return;
		}
		if (seededPaths.remove(octPath)) {
			// Content was already pushed to Yjs from guestOpenedEditor's no-UI
			// path (a guest opened this file before the host did). The listeners
			// are now attached via registerEditor above, but resending the
			// content here would be a second full-document replace. That seed
			// happened via the isHost-synchronous path in registerYjsObject (no
			// network round-trip needed), so it's already safe to send live edits.
			enableSendUpdates(octPath);
			return;
		}

		IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
		String text = document != null ? document.get() : "";
		if (text.isEmpty()) {
			// Defend against the document provider not having finished loading yet
			// (e.g. when the part was just force-opened for a guest) — fall back to
			// reading the resource directly rather than seeding Yjs with a blank doc.
			try {
				String diskText = readFileContent(file);
				if (!diskText.isEmpty()) {
					text = diskText;
				}
			} catch (Exception e) {
				LOG.warning("Failed to read fallback content for: " + octPath + " - " + e.getMessage());
			}
		}
		// Seed Yjs with real content. Empty string would make
		// guests re-read a blank buffer when switching tabs or on FileChange Update.
		remoteService.openDocument("text", octPath, text);
		confirmSeedThenEnableUpdates(octPath, text);
	}

	/**
	 * {@code awareness/openDocument} is fire-and-forget, and for non-host peers
	 * the content it carries is applied to the shared Yjs document asynchronously
	 * (the local replica only catches up once the seed round-trips through the
	 * OCT server — see {@code CollaborationInstance.registerYjsObject} in
	 * open-collaboration-service-process, which discards a guest's own
	 * {@code text} argument and simply notifies the host). If a local edit is
	 * sent before that sync lands, its offset is computed against an
	 * empty/stale replica; Yjs silently clamps out-of-range offsets instead of
	 * rejecting them, corrupting the edit for every peer.
	 *
	 * <p>To close that race, {@code DocumentSyncListener} queues local edits
	 * made before its seed confirms instead of sending them (see
	 * {@link DocumentSyncListener#enableAndFlush()}) and only flushes them here,
	 * once {@code awareness/getDocumentContent} confirms this peer's own replica
	 * already holds {@code expectedContent}. Runs off the UI thread since it
	 * polls with blocking gets; if the seed still hasn't confirmed after
	 * {@link #SEED_SYNC_TIMEOUT_MS}, updates are enabled anyway (best effort) so
	 * a slow/unreachable server doesn't permanently freeze the editor's outgoing
	 * sync — logged as a warning since it means edits until it also settles may
	 * still race.
	 */
	private void confirmSeedThenEnableUpdates(String octPath, String expectedContent) {
		String normalizedExpected = expectedContent.replace("\r\n", "\n");
		CompletableFuture.runAsync(() -> {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEED_SYNC_TIMEOUT_MS);
			do {
				if (disposed.get() || findEditorState(octPath) == null) {
					return;
				}
				try {
					FileContent content = remoteService.getDocumentContent(octPath).get(2, TimeUnit.SECONDS);
					String actual = (content != null && content.content != null)
							? new String(content.content, StandardCharsets.UTF_8).replace("\r\n", "\n")
							: null;
					if (normalizedExpected.equals(actual)) {
						enableSendUpdates(octPath);
						return;
					}
				} catch (Exception e) {
					LOG.log(Level.FINE, "Seed confirmation check failed for " + octPath, e);
				}
				try {
					Thread.sleep(SEED_SYNC_POLL_INTERVAL_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			} while (System.nanoTime() < deadline);
			LOG.warning("Seed for '" + octPath + "' did not confirm sync within " + SEED_SYNC_TIMEOUT_MS
					+ "ms; enabling live updates anyway (edits sent before the seed lands may still be corrupted)");
			enableSendUpdates(octPath);
		});
	}

	private void enableSendUpdates(String octPath) {
		EditorState state = findEditorState(octPath);
		if (state != null) {
			state.docListener.enableAndFlush();
		}
	}

	private String octPath(IFile file) {
		return OctPaths.fromEditorFile(file, isHost);
	}

	/**
	 * Resolve an incoming protocol path to an {@link IFile}. Host: workspace
	 * root ({@code sharedRoot} is the project name). Guest: temp project
	 * ({@code sharedRoot} is a linked folder). Returns {@code null} if the
	 * resource does not exist — callers must not NPE on that.
	 */
	private IFile eclipseFile(String octPath) {
		String normalized = OctPaths.normalize(octPath);
		if (normalized.isEmpty()) {
			return null;
		}
		IFile iFile = isHost
				? project.getWorkspace().getRoot().getFile(new Path(normalized))
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

	@Override
	public void partActivated(IWorkbenchPartReference ref) {
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
		EditorState existing = findEditorState(octPath);
		if (existing != null) {
			return false;
		}

		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (document == null) {
			return false;
		}

		// Pure echo guard now (see DocumentSyncListener's class doc): toggled off
		// only while applying a remote edit or save-triggered document.set() to
		// this same IDocument, so defaults to enabled. Seed-confirmation gating is
		// handled independently by DocumentSyncListener's own queue — see
		// confirmSeedThenEnableUpdates() / DocumentSyncListener.enableAndFlush().
		AtomicBoolean sendUpdates = new AtomicBoolean(true);

		DocumentSyncListener docListener = new DocumentSyncListener(octPath, remoteService, sendUpdates);
		document.addDocumentListener(docListener);

		ISourceViewer viewer = getSourceViewer(editor);

		SelectionSyncListener selListener = new SelectionSyncListener(octPath, remoteService, "self");
		if (editor.getSelectionProvider() instanceof IPostSelectionProvider postSelect) {
			postSelect.addPostSelectionChangedListener(selListener);
		} else if (viewer != null) {
			viewer.getSelectionProvider().addSelectionChangedListener(selListener);
		}

		// Attach peer annotation model
		AnnotationModel peerModel = new AnnotationModel();
		if (viewer != null) {
			IAnnotationModel baseModel = viewer.getAnnotationModel();
			if (baseModel instanceof IAnnotationModelExtension ext) {
				ext.addAnnotationModel(PEER_MODEL_KEY, peerModel);
			}
		}

		// Install drawing strategies
		PeerCursorDrawingStrategy cursorStrategy = new PeerCursorDrawingStrategy();
		PeerSelectionDrawingStrategy selectionStrategy = new PeerSelectionDrawingStrategy();
		if (viewer != null) {
			installAnnotationPainter(viewer, cursorStrategy, selectionStrategy);
		}

		editorStates.put(octPath, new EditorState(editor, document, peerModel, docListener, selListener, sendUpdates,
				cursorStrategy, selectionStrategy));
		LOG.info("Registered editor for: " + octPath);
		return true;
	}

	private void unregisterEditor(String octPath) {
		EditorState state = findEditorState(octPath);
		if (state == null) {
			return;
		}

		state.document.removeDocumentListener(state.docListener);
		if (state.selListener != null) {
			ISourceViewer viewer = getSourceViewer(state.editor);
			if (viewer != null) {
				viewer.getSelectionProvider().removeSelectionChangedListener(state.selListener);
				IAnnotationModel baseModel = viewer.getAnnotationModel();
				if (baseModel instanceof IAnnotationModelExtension ext) {
					ext.removeAnnotationModel(PEER_MODEL_KEY);
				}
			}
		}
		state.cursorStrategy.dispose();
		state.selectionStrategy.dispose();
		editorStates.values().remove(state);
		nameTagEpoch.remove(state);
	}

	/**
	 * {@code ITextEditor} has no public accessor for its underlying
	 * {@code ISourceViewer}. {@code getAdapter(ISourceViewer.class)} only works for
	 * editors that explicitly register that adapter, which excludes e.g. the
	 * Generic Editor's {@code ExtensionBasedTextEditor} (used for plain text files
	 * with no dedicated editor). Every Eclipse text editor, including that one,
	 * does extend {@code AbstractTextEditor} though, which declares a protected
	 * no-arg {@code getSourceViewer()} — fall back to that via reflection.
	 */
	private static ISourceViewer getSourceViewer(ITextEditor editor) {
		if (!(editor instanceof AbstractTextEditor)) {
			return null;
		}
		try {
			Method method = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer");
			method.setAccessible(true);
			return (ISourceViewer) method.invoke(editor);
		} catch (Exception e) {
			LOG.warning("Failed to obtain source viewer for " + editor.getClass().getName() + ": " + e.getMessage());
			return null;
		}
	}

	private void installAnnotationPainter(ISourceViewer viewer, PeerCursorDrawingStrategy cursorStrat,
			PeerSelectionDrawingStrategy selStrat) {
		try {
			AnnotationPainter painter = new AnnotationPainter(viewer, null);
			painter.addDrawingStrategy("org.eclipse.oct.cursor", cursorStrat);
			painter.addAnnotationType(PeerAnnotation.TYPE_CURSOR, "org.eclipse.oct.cursor");
			painter.addDrawingStrategy("org.eclipse.oct.selection", selStrat);
			painter.addAnnotationType(PeerAnnotation.TYPE_SELECTION, "org.eclipse.oct.selection");
			painter.setAnnotationTypeColor(PeerAnnotation.TYPE_CURSOR,
					viewer.getTextWidget().getDisplay().getSystemColor(SWT.COLOR_BLUE));
			painter.setAnnotationTypeColor(PeerAnnotation.TYPE_SELECTION,
					viewer.getTextWidget().getDisplay().getSystemColor(SWT.COLOR_CYAN));

			if (viewer instanceof ITextViewerExtension2 ext2) {
				ext2.addPainter(painter);
			}
		} catch (Exception e) {
			LOG.warning("Failed to install AnnotationPainter: " + e.getMessage());
		}
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
		Display.getDefault().asyncExec(() -> {
			EditorState state = findEditorState(octPath);
			if (state == null) {
				return;
			}

			state.sendUpdates.set(false);
			DocumentRewriteSession session = null;
			IDocument doc = state.document;
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
				state.sendUpdates.set(true);
			}
		});
	}

	/**
	 * Guest editors load from EFS before Yjs is seeded. The seed then arrives as
	 * insert-at-0 of the <em>entire</em> current buffer. Applying that prepends
	 * and duplicates. A one-character insert at 0 (typing at the start) must
	 * still go through — {@code "2"} into {@code "1"} is a real edit.
	 */
	static boolean isSeedEcho(IDocument doc, int start, int length, String text) {
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
		Display.getDefault().asyncExec(() -> {
			EditorState state = findEditorState(octPath);
			if (state == null) {
				return;
			}

			IDocument doc = state.document;
			int docLen = doc.getLength();

			// Build new annotation map, removing old peer annotations
			List<Annotation> toRemove = new ArrayList<>();
			state.peerModel.getAnnotationIterator().forEachRemaining(toRemove::add);

			Map<Annotation, Position> toAdd = new HashMap<>();
			if (selections != null) {
				for (ClientTextSelection sel : selections) {
					if (sel.peer == null) {
						continue;
					}
					RGB color = peerColors.getColor(sel.peer);
					String name = peerNameLookup.apply(sel.peer);

					int caretOffset = Math.min(sel.start, docLen);
					toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_CURSOR, sel.peer, name, color, true),
							new Position(caretOffset, 0));

					if (sel.end != null && sel.end != sel.start) {
						int start = Math.min(Math.min(sel.start, sel.end), docLen);
						int end = Math.min(Math.max(sel.start, sel.end), docLen);
						if (end > start) {
							toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_SELECTION, sel.peer, name, color, false),
									new Position(start, end - start));
						}
					}

					recordPeerDocument(sel.peer, octPath);

					// Follow mode
					if (sel.peer.equals(followingPeerId)) {
						followTo(eclipseFile(octPath), caretOffset);
					}
				}
			}

			state.peerModel.replaceAnnotations(toRemove.toArray(new Annotation[0]), toAdd);
			scheduleHideNameTags(state);
		});
	}

	private void scheduleHideNameTags(EditorState state) {
		int epoch = nameTagEpoch.merge(state, 1, Integer::sum);
		Display.getDefault().timerExec(NAME_TAG_VISIBLE_MS, () -> {
			if (disposed.get() || !Integer.valueOf(epoch).equals(nameTagEpoch.get(state))) {
				return;
			}
			state.peerModel.getAnnotationIterator().forEachRemaining(annotation -> {
				if (annotation instanceof PeerAnnotation peer) {
					peer.setShowName(false);
				}
			});
			ISourceViewer viewer = getSourceViewer(state.editor);
			if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
				viewer.getTextWidget().redraw();
			}
		});
	}

	/**
	 * Called on the host when a guest opens an editor. Regardless of the "follow
	 * guest selection" setting, the Yjs doc must be seeded with real content or the
	 * guest ends up looking at a blank buffer. Only actually stealing the host's
	 * editor focus (opening/activating the part) is gated behind
	 * {@link #followGuestSelection}.
	 */
	public void guestOpenedEditor(String documentPath) {
		Display.getDefault().asyncExec(() -> {
			String path = OctPaths.normalize(documentPath);
			IFile file = eclipseFile(path);
			if (file == null) {
				return;
			}

			EditorState existing = findEditorState(path);
			if ((existing != null) || seededPaths.contains(documentPath)) {
				// Already pushed once via the no-UI path below (e.g. a second
				// guest opening the same file before the host does) — sending
				// openDocument again would be a needless full-document replace.
				if (followGuestSelection) {
					activateEditor(file);
				}
				return;
			}

			if (followGuestSelection) {
				// Opens the part, which triggers partOpened() -> first-time
				// registration -> real content sent exactly once.
				activateEditor(file);
			}
		});
	}

	private void activateEditor(IFile file) {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			IDE.openEditor(page, file, true);
		} catch (Exception e) {
			LOG.warning("Failed to open editor for guest: " + e.getMessage());
		}
	}

	private static String readFileContent(IFile file) throws Exception {
		try (java.io.InputStream is = file.getContents()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
			IEditorPart editor = IDE.openEditor(page, file, false);
			if (editor instanceof ITextEditor textEditor) {
				textEditor.selectAndReveal(offset, 0);
			}
		} catch (Exception e) {
			LOG.warning("Follow-mode failed: " + e.getMessage());
		}
	}

	public void followPeer(String peerId) {
		this.followingPeerId = peerId;
		onPresenceChanged.fire(null);
	}

	public void stopFollowing() {
		this.followingPeerId = null;
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

	public void forgetPeer(String peerId) {
		if (peerId != null) {
			peerDocumentPaths.remove(peerId);
		}
	}

	public String getFollowingPeerId() {
		return this.followingPeerId;
	}

	/**
	 * Save an open editor for {@code path} so its dirty state is cleared. Used when
	 * a peer persists the file (guest→host or host→guest {@code writeFile}).
	 * Returns {@code true} if handled by an open editor.
	 */
	public boolean saveIfOpen(String path, byte[] content) {
		if (findEditorState(path) == null) {
			return false;
		}
		AtomicBoolean handled = new AtomicBoolean(false);
		Display.getDefault().syncExec(() -> {
			EditorState state = findEditorState(path);
			if (state == null) {
				return;
			}
			// The document is normally already up to date via Yjs sync; only
			// replace it if the incoming content actually differs, suppressing
			// echo so we don't broadcast the change back to peers.
			if (content != null) {
				String incoming = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
				if (!incoming.equals(state.document.get())) {
					state.sendUpdates.set(false);
					try {
						state.document.set(incoming);
					} finally {
						state.sendUpdates.set(true);
					}
				}
			}
			try {
				if (isHost) {
					state.editor.doSave(new NullProgressMonitor());
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
	 * without {@code doSave()} — that uses overwrite=false and would prompt
	 * "file has changed on the file system" after {@code refreshLocal} updates
	 * the oct:// stamp, and would also echo {@code writeFile} back to the host.
	 */
	private void acceptRemoteSave(EditorState state) throws CoreException {
		IDocumentProvider provider = state.editor.getDocumentProvider();
		IEditorInput input = state.editor.getEditorInput();
		if (provider == null || input == null || !provider.canSaveDocument(input)) {
			return;
		}
		state.sendUpdates.set(false);
		try {
			OctFileStore.suppressWrite(() -> {
				try {
					provider.saveDocument(new NullProgressMonitor(), input, state.document, true);
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
			state.sendUpdates.set(true);
		}
	}

	private EditorState findEditorState(String octPath) {
		if (octPath == null) {
			return null;
		}
		EditorState exact = editorStates.get(OctPaths.normalize(octPath));
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, EditorState> entry : editorStates.entrySet()) {
			if (OctPaths.referToSameDocument(entry.getKey(), octPath)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public void dispose() {
		disposed.set(true);
		seededPaths.clear();
		peerDocumentPaths.clear();
		Display.getDefault().asyncExec(() -> {
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

	// ---- Inner state holder ----

	private record EditorState(ITextEditor editor, IDocument document, AnnotationModel peerModel,
			DocumentSyncListener docListener, SelectionSyncListener selListener, AtomicBoolean sendUpdates,
			PeerCursorDrawingStrategy cursorStrategy, PeerSelectionDrawingStrategy selectionStrategy) {
	}
}
