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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
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
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.AbstractTextEditor;
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
		if (file == null || !file.exists()) {
			return;
		}
		if (file.getProject() != project) {
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
			// content here would be a second full-document replace.
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
	}

	/**
	 * If we host, return workspace relative path. If we guesting resolves to temp
	 * project relative path.
	 * 
	 * @param file
	 * @return
	 */
	private String octPath(IFile file) {
		return isHost ? file.getFullPath().toString() : file.getProjectRelativePath().toString();
	}

	/**
	 * Return IFile for the given OCT path. If we host then octPath is handled the
	 * workspace relative path. If we are guest the path is resolved as relative
	 * path inside the temp project.
	 * 
	 * @param octPath
	 * @return
	 */
	private IFile eclipseFile(String octPath) {
		var iFile = isHost ? project.getWorkspace().getRoot().getFile(new Path(octPath)) : project.getFile(octPath);
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
		editorStates.remove(octPath);
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
						followTo(eclipseFile(octPath), caretOffset);
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
			IFile file = eclipseFile(documentPath);
			if (!file.exists()) {
				return;
			}

			EditorState existing = findEditorState(documentPath);
			if (existing != null) {
				// Already open/registered on the host — content is already
				// live-synced, so only bring it to front if opted in.
				if (followGuestSelection) {
					activateEditor(file);
				}
				return;
			}

			if (seededPaths.contains(documentPath)) {
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
		this.followGuestSelection = followGuestSelection;
	}

	private void followTo(IFile file, int offset) {
		try {
			if (!file.exists()) {
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

	/**
	 * Resolve editor state by exact OCT path, or by suffix so a guest temp project
	 * ({@code name-oct-…/root/file}) matches a host path ({@code root/file}).
	 */
	private EditorState findEditorState(String octPath) {
		if (octPath == null) {
			return null;
		}
		EditorState exact = editorStates.get(octPath);
		if (exact != null) {
			return exact;
		}
		return null;
	}

	public void dispose() {
		disposed.set(true);
		seededPaths.clear();
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
