/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.AnnotationPainter;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.swt.SWT;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * One editor's sync wiring: document/selection listeners, the peer annotation
 * model and the painter that draws peer cursors into it.
 *
 * <p>
 * {@link #attach} and {@link #detach()} must stay exact mirrors — a listener
 * removed from the wrong provider, or a painter left on the viewer, keeps
 * firing (or drawing with disposed colors) after the session ended. Both
 * halves live here precisely so that pairing is visible in one place rather
 * than spread across two distant methods.
 */
public class TrackedEditor {

	private static final Logger LOG = Logger.getLogger(TrackedEditor.class.getName());
	private static final Object PEER_MODEL_KEY = new Object();

	private final ITextEditor editor;
	private final IDocument document;
	private final AnnotationModel peerModel;
	private final DocumentSyncListener docListener;
	private final AtomicBoolean sendUpdates;

	private final SelectionSyncListener selListener;
	/** Non-null when selListener was added as a post-selection listener. */
	private final IPostSelectionProvider postSelectionProvider;
	/** Non-null when selListener was added to the viewer's provider instead. */
	private final ISelectionProvider viewerSelectionProvider;
	private final PeerCursorDrawingStrategy cursorStrategy;
	private final PeerSelectionDrawingStrategy selectionStrategy;
	/** Non-null unless the viewer was unavailable or the install failed. */
	private final AnnotationPainter painter;

	private TrackedEditor(ITextEditor editor, IDocument document, AnnotationModel peerModel,
			DocumentSyncListener docListener, AtomicBoolean sendUpdates, SelectionSyncListener selListener,
			IPostSelectionProvider postSelectionProvider, ISelectionProvider viewerSelectionProvider,
			PeerCursorDrawingStrategy cursorStrategy, PeerSelectionDrawingStrategy selectionStrategy,
			AnnotationPainter painter) {
		this.editor = editor;
		this.document = document;
		this.peerModel = peerModel;
		this.docListener = docListener;
		this.sendUpdates = sendUpdates;
		this.selListener = selListener;
		this.postSelectionProvider = postSelectionProvider;
		this.viewerSelectionProvider = viewerSelectionProvider;
		this.cursorStrategy = cursorStrategy;
		this.selectionStrategy = selectionStrategy;
		this.painter = painter;
	}

	/**
	 * Installs listeners and annotations for {@code editor}. Returns
	 * {@code null} when the editor has no document yet, in which case nothing
	 * was installed and the caller must not treat it as tracked.
	 */
	public static TrackedEditor attach(String octPath, ITextEditor editor, OCTService remoteService) {
		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (document == null) {
			return null;
		}

		// Pure echo guard now (see DocumentSyncListener's class doc): toggled off
		// only while applying a remote edit or save-triggered document.set() to
		// this same IDocument, so defaults to enabled. Seed-confirmation gating is
		// handled independently by DocumentSyncListener's own queue — see
		// DocumentSeedGate and enableAndFlush().
		AtomicBoolean sendUpdates = new AtomicBoolean(true);

		DocumentSyncListener docListener = new DocumentSyncListener(octPath, remoteService, sendUpdates);
		document.addDocumentListener(docListener);

		ISourceViewer viewer = sourceViewerOf(editor);

		// Remember which provider the listener actually went on: it is added either
		// as a *post*-selection listener on the editor's own provider or as a plain
		// one on the viewer's, and detach() has to undo exactly that pairing
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

		LOG.info("Registered editor for: " + octPath);
		return new TrackedEditor(editor, document, peerModel, docListener, sendUpdates, selListener,
				postSelectionProvider, viewerSelectionProvider, cursorStrategy, selectionStrategy, painter);
	}

	/** Undoes {@link #attach} exactly. Must run on the UI thread. */
	public void detach() {
		document.removeDocumentListener(docListener);
		// Mirror attach() exactly: the listener sits on whichever provider it was
		// added to, not necessarily the viewer's.
		if (selListener != null) {
			if (postSelectionProvider != null) {
				postSelectionProvider.removePostSelectionChangedListener(selListener);
			}
			if (viewerSelectionProvider != null) {
				viewerSelectionProvider.removeSelectionChangedListener(selListener);
			}
		}
		ISourceViewer viewer = sourceViewerOf(editor);
		if (viewer != null) {
			IAnnotationModel baseModel = viewer.getAnnotationModel();
			if (baseModel instanceof IAnnotationModelExtension ext) {
				ext.removeAnnotationModel(PEER_MODEL_KEY);
			}
			// Detach the painter before disposing the strategies below — a painter
			// left on the viewer can still be asked to draw, and would then use the
			// strategies' already-disposed Color/Font.
			if (painter != null && viewer instanceof ITextViewerExtension2 ext2) {
				ext2.removePainter(painter);
			}
		}
		if (painter != null) {
			painter.dispose();
		}
		cursorStrategy.dispose();
		selectionStrategy.dispose();
	}

	public ITextEditor editor() {
		return editor;
	}

	public IDocument document() {
		return document;
	}

	public AnnotationModel peerModel() {
		return peerModel;
	}

	/**
	 * Echo guard: cleared while a remote edit or save-driven {@code set()} is
	 * applied to this editor's document, so the change is not broadcast back.
	 */
	public AtomicBoolean sendUpdates() {
		return sendUpdates;
	}

	/** Releases edits queued while this editor's seed was still unconfirmed. */
	public void enableAndFlush() {
		docListener.enableAndFlush();
	}

	/** Repaints the widget so changed peer annotations become visible. */
	public void redraw() {
		ISourceViewer viewer = sourceViewerOf(editor);
		if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
			viewer.getTextWidget().redraw();
		}
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
	public static ISourceViewer sourceViewerOf(ITextEditor editor) {
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

	/** Returns the installed painter so {@link #detach()} can remove it again. */
	private static AnnotationPainter installAnnotationPainter(ISourceViewer viewer,
			PeerCursorDrawingStrategy cursorStrat, PeerSelectionDrawingStrategy selStrat) {
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
}
