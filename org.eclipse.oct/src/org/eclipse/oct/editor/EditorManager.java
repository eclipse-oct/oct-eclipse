/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.AnnotationPainter;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.oct.internal.fs.OctFileStore;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.FileContent;
import org.eclipse.oct.protocol.TextDocumentInsert;
import org.eclipse.oct.ui.PeerColors;
import org.eclipse.oct.util.EventEmitter;
import org.eclipse.oct.util.OctPaths;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
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
	private static final int NAME_TAG_VISIBLE_MS = 1900;

	private final OCTService remoteService;
	private final IProject project;
	private final PeerColors peerColors;
	private Function<String, String> peerNameLookup = id -> id;

	/**
	 * Per editor-path state. Concurrent because {@link #confirmSeedThenEnableUpdates}
	 * polls {@link #findEditorState} from a background thread while the UI thread
	 * mutates the map in {@link #registerEditor}/{@link #unregisterEditor} — a racy
	 * {@code null} there used to leave {@code DocumentSyncListener.pending}
	 * permanently unflushed (edits only ever reaching peers via a later save).
	 */
	private final Map<String, EditorState> editorStates = new ConcurrentHashMap<>();
	/**
	 * Bumps on each selection update so a stale hide-timer cannot clear a fresh
	 * name tag.
	 */
	private final Map<EditorState, Integer> nameTagEpoch = new IdentityHashMap<>();

	/**
	 * Paths this peer has already pushed to Yjs from {@link #guestOpenedEditor}'s
	 * no-UI path, i.e. without a local editor being open. Purely a
	 * send-it-once guard for that method: a second guest opening the same path
	 * before the host does must not trigger another disk read and push.
	 *
	 * <p>
	 * It deliberately does <em>not</em> suppress {@link #trackEditor}'s own
	 * {@code openDocument} — that call is what tells the service process (via
	 * {@code YjsNormalizedTextDocument.attachLocalDocument}) what this editor's
	 * buffer actually holds, and every offset it later reports is computed
	 * against that string. Skipping it corrupts offsets rather than saving work,
	 * and upstream already guards against a redundant reseed
	 * ({@code if (YjsDoc.getText(path).length > 0) return;} in
	 * {@code CollaborationInstance.registerYjsObject}).
	 */
	private final Set<String> seededPaths = ConcurrentHashMap.newKeySet();

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

	public EditorManager(OCTService remoteService, IProject project, boolean isHost, PeerColors peerColors) {
		this.remoteService = remoteService;
		this.project = project;
		this.peerColors = peerColors;
		this.isHost = isHost;
		registerPartListener();
	}

	/**
	 * Resolves a peer id to the display name shown on the cursor name tag. Defaults
	 * to the id itself when unset (tests / peers not yet in init).
	 */
	public void setPeerNameLookup(Function<String, String> peerNameLookup) {
		this.peerNameLookup = peerNameLookup != null ? peerNameLookup : id -> id;
	}

	private void registerPartListener() {
		Display.getDefault().syncExec(() -> {
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
	 * {@link EditorState} yet. Without this sweep a file the user had open before
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
		// A path already pushed by guestOpenedEditor's no-UI path still has to be
		// announced here: only openDocument tells the service process what *this
		// editor's buffer* holds (attachLocalDocument), and all the offsets it
		// later reports are computed against that string. It is also what keeps
		// the ADR-0002 seed gate on this path. Upstream ignores the redundant
		// content itself, so this is cheap rather than a full-document replace.
		seededPaths.remove(octPath);

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
	 * {@code awareness/openDocument} is fire-and-forget, and for non-host peers the
	 * content it carries is applied to the shared Yjs document asynchronously (the
	 * local replica only catches up once the seed round-trips through the OCT
	 * server — see {@code CollaborationInstance.registerYjsObject} in
	 * open-collaboration-service-process, which discards a guest's own {@code text}
	 * argument and simply notifies the host). If a local edit is sent before that
	 * sync lands, its offset is computed against an empty/stale replica; Yjs
	 * silently clamps out-of-range offsets instead of rejecting them, corrupting
	 * the edit for every peer.
	 *
	 * <p>
	 * To close that race, {@code DocumentSyncListener} queues local edits made
	 * before its seed confirms instead of sending them (see
	 * {@link DocumentSyncListener#enableAndFlush()}) and only flushes them here,
	 * once {@code awareness/getDocumentContent} confirms this peer's own replica
	 * already holds {@code expectedContent}. Runs off the UI thread since it polls
	 * with blocking gets; if the seed still hasn't confirmed after
	 * {@link #SEED_SYNC_TIMEOUT_MS}, updates are enabled anyway (best effort) so a
	 * slow/unreachable server doesn't permanently freeze the editor's outgoing sync
	 * — logged as a warning since it means edits until it also settles may still
	 * race.
	 */
	private void confirmSeedThenEnableUpdates(String octPath, String expectedContent) {
		String normalizedExpected = expectedContent.replace("\r\n", "\n");
		CompletableFuture.runAsync(() -> {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEED_SYNC_TIMEOUT_MS);
			do {
				if (disposed.get()) {
					return;
				}
				if (findEditorState(octPath) == null) {
					// The editor was closed (or never registered) while we polled.
					// Log it: silently returning here leaves DocumentSyncListener
					// unconfirmed forever, so queued edits are never flushed and the
					// file appears to only sync on save.
					LOG.warning("Seed confirmation for '" + octPath
							+ "' abandoned: no editor state registered; outgoing sync stays gated for it");
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

		// Remember which provider the listener actually went on: it is added either
		// as a *post*-selection listener on the editor's own provider or as a plain
		// one on the viewer's, and unregisterEditor has to undo exactly that pairing
		// (removing it from the wrong provider leaves it firing after the session).
		SelectionSyncListener selListener = new SelectionSyncListener(octPath, remoteService, "self");
		IPostSelectionProvider postSelectionProvider = null;
		ISelectionProvider viewerSelectionProvider = null;
		if (editor.getSelectionProvider() instanceof IPostSelectionProvider postSelect) {
			postSelectionProvider = postSelect;
			postSelect.addPostSelectionChangedListener(selListener);
		} else if (viewer != null) {
			viewerSelectionProvider = viewer.getSelectionProvider();
			viewerSelectionProvider.addSelectionChangedListener(selListener);
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
		AnnotationPainter painter = viewer != null ? installAnnotationPainter(viewer, cursorStrategy, selectionStrategy)
				: null;

		editorStates.put(octPath, new EditorState(editor, document, peerModel, docListener, selListener, sendUpdates,
				cursorStrategy, selectionStrategy, postSelectionProvider, viewerSelectionProvider, painter));
		LOG.info("Registered editor for: " + octPath);
		return true;
	}

	private void unregisterEditor(String octPath) {
		EditorState state = findEditorState(octPath);
		if (state == null) {
			return;
		}

		state.document.removeDocumentListener(state.docListener);
		// Mirror registerEditor exactly: the listener sits on whichever provider it
		// was added to, not necessarily the viewer's.
		if (state.selListener != null) {
			if (state.postSelectionProvider != null) {
				state.postSelectionProvider.removePostSelectionChangedListener(state.selListener);
			}
			if (state.viewerSelectionProvider != null) {
				state.viewerSelectionProvider.removeSelectionChangedListener(state.selListener);
			}
		}
		ISourceViewer viewer = getSourceViewer(state.editor);
		if (viewer != null) {
			IAnnotationModel baseModel = viewer.getAnnotationModel();
			if (baseModel instanceof IAnnotationModelExtension ext) {
				ext.removeAnnotationModel(PEER_MODEL_KEY);
			}
			// Detach the painter before disposing the strategies below — a painter
			// left on the viewer can still be asked to draw, and would then use the
			// strategies' already-disposed Color/Font.
			if (state.painter != null && viewer instanceof ITextViewerExtension2 ext2) {
				ext2.removePainter(state.painter);
			}
		}
		if (state.painter != null) {
			state.painter.dispose();
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

	/** Returns the installed painter so {@link #unregisterEditor} can detach it. */
	private AnnotationPainter installAnnotationPainter(ISourceViewer viewer, PeerCursorDrawingStrategy cursorStrat,
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
			return painter;
		} catch (Exception e) {
			LOG.warning("Failed to install AnnotationPainter: " + e.getMessage());
			return null;
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
	 * insert-at-0 of the <em>entire</em> current buffer. Applying that prepends and
	 * duplicates. A one-character insert at 0 (typing at the start) must still go
	 * through — {@code "2"} into {@code "1"} is a real edit.
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
			// Record each peer's current document (for the Session View "File" column)
			// and react to follow-mode *before* checking for a local EditorState —
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

			EditorState state = findEditorState(octPath);
			if (state != null) {
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
					}
				}

				state.peerModel.replaceAnnotations(toRemove.toArray(new Annotation[0]), toAdd);
				scheduleHideNameTags(state);
			}

			// A reporting peer's selections just replaced (or, if state was null,
			// simply confirmed) its presence in octPath. No empty update is ever sent
			// for a path a peer just left (see EditorManager class doc / the exec
			// plan this closes) — so without this sweep, its annotations from a
			// *previously* open file would linger there forever.
			if (!reportingPeers.isEmpty()) {
				for (Map.Entry<String, EditorState> entry : editorStates.entrySet()) {
					if (OctPaths.referToSameDocument(entry.getKey(), octPath)) {
						continue;
					}
					for (String peerId : reportingPeers) {
						removePeerAnnotations(entry.getValue(), peerId);
					}
				}
			}
		});
	}

	/**
	 * Removes every {@link PeerAnnotation} belonging to {@code peerId} from
	 * {@code state}'s peer annotation model and repaints the widget. Used both
	 * for a departed peer ({@link #forgetPeer}) and for a peer that switched to a
	 * different file ({@link #updateTextSelection}) — neither case ever arrives
	 * over the wire as an explicit empty update (see the class doc on why), so
	 * cleanup has to be driven locally.
	 */
	private void removePeerAnnotations(EditorState state, String peerId) {
		List<Annotation> toRemove = new ArrayList<>();
		state.peerModel.getAnnotationIterator().forEachRemaining(annotation -> {
			if (annotation instanceof PeerAnnotation peer && peerId.equals(peer.getPeerId())) {
				toRemove.add(annotation);
			}
		});
		if (toRemove.isEmpty()) {
			return;
		}
		state.peerModel.replaceAnnotations(toRemove.toArray(new Annotation[0]), Map.of());
		ISourceViewer viewer = getSourceViewer(state.editor);
		if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
			viewer.getTextWidget().redraw();
		}
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
		Display.getDefault().asyncExec(() -> {
			String path = OctPaths.normalize(documentPath);
			IFile file = eclipseFile(path);
			if (file == null) {
				return;
			}

			boolean alreadyKnown = findEditorState(path) != null || !seededPaths.add(path);

			if (followGuestSelection) {
				// Opens the part, which triggers partOpened() -> trackEditor(), which
				// seeds from the live buffer and removes the seededPaths entry again.
				activateEditor(file);
			} else if (!alreadyKnown) {
				seedFromDisk(file, path);
			}
		});
	}

	/**
	 * Pushes {@code file}'s on-disk content as the shared document seed without
	 * opening an editor for it. Runs off the UI thread: it does I/O, and
	 * {@code openDocument} may block on the service-process pipe.
	 */
	private void seedFromDisk(IFile file, String path) {
		CompletableFuture.runAsync(() -> {
			try {
				remoteService.openDocument("text", path, readFileContent(file));
			} catch (Exception e) {
				LOG.warning("Failed to seed '" + path + "' from disk for a guest: " + e.getMessage());
				// Let a later open retry rather than leaving the path marked as seeded.
				seededPaths.remove(path);
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
		try (InputStream is = file.getContents()) {
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
			// activate=true: bring the tab to the front (and (re)open it if the
			// user had closed it) instead of just updating a background editor —
			// otherwise the follower never actually sees the tab switch.
			IEditorPart editor = IDE.openEditor(page, file, true);
			if (editor instanceof ITextEditor textEditor) {
				// Clamp against the just-opened editor's own document rather than
				// relying on a pre-existing local EditorState (there may be none yet
				// if we're following into a file we haven't opened before).
				IDocument doc = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
				int clamped = doc != null ? Math.max(0, Math.min(offset, doc.getLength())) : Math.max(0, offset);
				// Scroll the viewport to the peer's location without touching our
				// own caret/selection — selectAndReveal() would move *our* cursor
				// there too, which is surprising and would itself broadcast a new
				// selection update. ISourceViewer#revealRange only scrolls.
				ISourceViewer viewer = getSourceViewer(textEditor);
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
		Display.getDefault().asyncExec(() -> {
			for (EditorState state : editorStates.values()) {
				removePeerAnnotations(state, peerId);
			}
		});
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
	 * without {@code doSave()} — that uses overwrite=false and would prompt "file
	 * has changed on the file system" after {@code refreshLocal} updates the oct://
	 * stamp, and would also echo {@code writeFile} back to the host.
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
		followingPeerId = null;
		followGuestSelection = false;
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
			PeerCursorDrawingStrategy cursorStrategy, PeerSelectionDrawingStrategy selectionStrategy,
			/** Non-null when selListener was added as a post-selection listener. */
			IPostSelectionProvider postSelectionProvider,
			/** Non-null when selListener was added to the viewer's provider instead. */
			ISelectionProvider viewerSelectionProvider,
			/** Non-null unless the viewer was unavailable or the install failed. */
			AnnotationPainter painter) {
	}
}
