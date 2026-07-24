/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
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
import org.eclipse.oct.internal.PeerColors;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Full M4 EditorManager: tracks open editors, syncs documents and selections,
 * renders peer cursors/selections via AnnotationPainter, supports follow-mode.
 */
public class EditorManager implements IPartListener2 {

	private static final Logger LOG = Logger.getLogger(EditorManager.class.getName());
	private static final Object PEER_MODEL_KEY = new Object();

	private final OCTService remoteService;
	private final IProject project;
	private final PeerColors peerColors;

	/** Per editor-path state */
	private final Map<String, EditorState> editorStates = new HashMap<>();

	private final AtomicBoolean disposed = new AtomicBoolean(false);

	public String followingPeerId = null;

	/**
	 * When {@code true}, a guest opening/selecting a file steals the host's editor
	 * focus (auto-opens and activates it). Off by default since this is disruptive
	 * to the host's own workflow.
	 */
	private volatile boolean followGuestSelection = false;

	public EditorManager(OCTService remoteService, IProject project) {
		this.remoteService = remoteService;
		this.project = project;
		this.peerColors = new PeerColors();
		registerPartListener();
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
		if (disposed.get()) {
			return;
		}
		if (!(ref instanceof IEditorReference editorRef)) {
			return;
		}
		// Use the opened editor, not the active one — otherwise opening a second
		// file while another stays active seeds the wrong (or empty) Yjs doc.
		IEditorPart editor = editorRef.getEditor(false);
		if (!(editor instanceof ITextEditor textEditor)) {
			return;
		}
		IFile file = editor.getEditorInput().getAdapter(IFile.class);
		if (file == null) {
			return;
		}
		String path = pathFor(file);
		boolean firstRegistration = registerEditor(path, textEditor);
		if (!firstRegistration) {
			// Already tracked — do not resend. openDocument always does a full
			// delete+insert on the Yjs side (no diffing), so re-sending unchanged
			// content on every partOpened marks guest editors dirty for no reason.
			return;
		}

		IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
		String text = document != null ? document.get() : "";
		if (text.isEmpty()) {
			// Defend against the document provider not having finished loading yet
			// (e.g. when the part was just force-opened for a guest) — fall back to
			// reading the resource directly rather than seeding Yjs with a blank doc.
			try {
				if (file.exists()) {
					String diskText = readFileContent(file);
					if (!diskText.isEmpty()) {
						text = diskText;
					}
				}
			} catch (Exception e) {
				LOG.warning("Failed to read fallback content for: " + path + " - " + e.getMessage());
			}
		}
		// Seed Yjs with real content. Empty string would make
		// guests re-read a blank buffer when switching tabs or on FileChange Update.
		remoteService.openDocument("text", path, text);
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
		unregisterEditor(pathFor(file));
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
	private boolean registerEditor(String path, ITextEditor editor) {
		if (editorStates.containsKey(path)) {
			return false;
		}

		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (document == null) {
			return false;
		}

		AtomicBoolean sendUpdates = new AtomicBoolean(true);

		DocumentSyncListener docListener = new DocumentSyncListener(path, remoteService, sendUpdates);
		document.addDocumentListener(docListener);

		ISourceViewer viewer = editor.getAdapter(ISourceViewer.class);
		SelectionSyncListener selListener = null;
		if (viewer != null) {
			selListener = new SelectionSyncListener(path, remoteService, "self");
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

		editorStates.put(path, new EditorState(editor, document, peerModel, docListener, selListener, sendUpdates,
				cursorStrategy, selectionStrategy));
		LOG.fine("Registered editor for: " + path);
		return true;
	}

	private void unregisterEditor(String path) {
		EditorState state = editorStates.remove(path);
		if (state == null) {
			return;
		}

		state.document.removeDocumentListener(state.docListener);
		if (state.selListener != null) {
			ISourceViewer viewer = state.editor.getAdapter(ISourceViewer.class);
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
	}

	private void installAnnotationPainter(ISourceViewer viewer, PeerCursorDrawingStrategy cursorStrat,
			PeerSelectionDrawingStrategy selStrat) {
		try {
			AnnotationPainter painter = new AnnotationPainter(viewer, null);
			painter.addDrawingStrategy("org.eclipse.oct.cursor", cursorStrat);
			painter.addAnnotationType(PeerAnnotation.TYPE_CURSOR, "org.eclipse.oct.cursor");
			painter.addDrawingStrategy("org.eclipse.oct.selection", selStrat);
			painter.addAnnotationType(PeerAnnotation.TYPE_SELECTION, "org.eclipse.oct.selection");
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
	public void updateDocument(String path, TextDocumentInsert[] updates) {
		if (updates == null || updates.length == 0) {
			return;
		}
		Display.getDefault().asyncExec(() -> {
			EditorState state = editorStates.get(path);
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
					// Clamp to document length
					int docLen = doc.getLength();
					start = Math.min(start, docLen);
					length = Math.min(length, docLen - start);
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
	 * Update peer cursor/selection annotations. Port of
	 * EditorManager.updateTextSelection.
	 */
	public void updateTextSelection(String path, ClientTextSelection[] selections) {
		Display.getDefault().asyncExec(() -> {
			EditorState state = editorStates.get(path);
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

					int caretOffset = Math.min(sel.start, docLen);
					// Zero-length caret annotation
					toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_CURSOR, sel.peer, color),
							new Position(caretOffset, 0));

					// Selection annotation (when start != end)
					if (sel.end != null && sel.end != sel.start) {
						int start = Math.min(Math.min(sel.start, sel.end), docLen);
						int end = Math.min(Math.max(sel.start, sel.end), docLen);
						if (end > start) {
							toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_SELECTION, sel.peer, color),
									new Position(start, end - start));
						}
					}

					// Follow mode
					if (sel.peer.equals(followingPeerId)) {
						followTo(path, caretOffset);
					}
				}
			}

			state.peerModel.replaceAnnotations(toRemove.toArray(new Annotation[0]), toAdd);
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
			String relPath = documentPath.replaceFirst("^[^/]+/", "");
			IFile file = project.getFile(relPath);
			if (!file.exists()) {
				return;
			}

			if (editorStates.containsKey(documentPath)) {
				// Already open/registered on the host — content is already
				// live-synced, so only bring it to front if opted in.
				if (followGuestSelection) {
					activateEditor(file);
				}
				return;
			}

			if (followGuestSelection) {
				// Opens the part, which triggers partOpened() -> first-time
				// registration -> real content sent exactly once.
				activateEditor(file);
			} else {
				// Don't steal focus. Read the current on-disk content directly
				// (avoids any race with an editor's document still loading)
				// and seed Yjs with it so the guest doesn't see an empty file.
				try {
					String text = readFileContent(file);
					remoteService.openDocument("text", documentPath, text);
				} catch (Exception e) {
					LOG.warning("Failed to read file for guest: " + e.getMessage());
				}
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
		this.followGuestSelection = followGuestSelection;
	}

	private void followTo(String path, int offset) {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			String relPath = path.replaceFirst("^[^/]+/", "");
			IFile file = project.getFile(relPath);
			if (!file.exists()) {
				return;
			}
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
	}

	public void stopFollowing() {
		this.followingPeerId = null;
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
			// echo so we don't broadcast the change back to guests.
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
				state.editor.doSave(new NullProgressMonitor());
				handled.set(true);
			} catch (Exception e) {
				LOG.warning("Failed to save editor for: " + path + " - " + e.getMessage());
			}
		});
		return handled.get();
	}

	private String pathFor(IFile file) {
		return file.getProject().getName() + "/" + file.getProjectRelativePath().toString();
	}

	/**
	 * Resolve editor state by exact OCT path, or by suffix so a guest temp project
	 * ({@code name-oct-…/root/file}) matches a host path ({@code root/file}).
	 */
	private EditorState findEditorState(String path) {
		if (path == null) {
			return null;
		}
		EditorState exact = editorStates.get(path);
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, EditorState> entry : editorStates.entrySet()) {
			String key = entry.getKey();
			if (key.endsWith("/" + path) || path.endsWith("/" + key)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public void dispose() {
		disposed.set(true);
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
