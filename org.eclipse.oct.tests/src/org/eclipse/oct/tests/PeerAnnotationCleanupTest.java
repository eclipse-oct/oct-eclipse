/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.oct.editor.EditorManager;
import org.eclipse.oct.editor.PeerAnnotation;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.Peer;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestPeer;
import org.eclipse.oct.util.OctPaths;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * Local-only regression tests for the "a departed/switched peer leaves ghost
 * annotations" bug: {@code EditorManager.forgetPeer} previously only cleared
 * {@code peerDocumentPaths} and never touched the per-editor
 * {@code AnnotationModel}, and {@code updateTextSelection} never swept a
 * peer's annotations out of a file it had switched away from. Neither case
 * ever arrives over the wire as an explicit empty update (see
 * {@code EditorManager.updateTextSelection}'s class doc), so both are driven
 * directly against the real {@link EditorManager} and
 * {@link CollaborationInstance} here rather than via
 * an actual second peer disconnecting — {@link HandshakeTest} already covers
 * the real wire path for {@code peerLeft} itself.
 */
@TestInstance(Lifecycle.PER_CLASS)
class PeerAnnotationCleanupTest {

	private static final String SEED_CONTENT = "line one\nline two\nline three\n";
	private static final String PEER_ID = "peer-1";

	private String serverUrl;
	private TestPeer host;
	private IProject hostProject;
	private ITextEditor openEditor;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("peer-annotation-cleanup-host");
		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
	}

	@AfterEach
	void closeSession() {
		closeOpenEditor();
		if (host != null) {
			host.close();
		}
		EclipseTestProjects.deleteProject(hostProject);
	}

	@Test
	@DisplayName("peerLeft removes the departed peer's annotations and resets follow state")
	void peerLeftClearsPeerAnnotations() throws Exception {
		IFile file = EclipseTestProjects.writeFile(hostProject, "a.txt", SEED_CONTENT);
		openHostEditor(file);
		host.createRoom(hostProject, new Workspace(hostProject.getName(), new String[] { hostProject.getName() }));
		String octPath = OctPaths.fromHostResource(file);

		host.editorManager().followPeer(PEER_ID);
		assertTrue(host.editorManager().isFollowGuestSelection(), "precondition: follow must be armed");
		assertTrue(PEER_ID.equals(host.editorManager().getFollowingPeerId()), "precondition: following the peer");

		host.editorManager().updateTextSelection(octPath,
				new ClientTextSelection[] { new ClientTextSelection(PEER_ID, 2, 15, false) });
		pumpUiEvents();

		List<PeerAnnotation> before = peerAnnotations(openEditor);
		assertFalse(before.isEmpty(), "the peer's selection must render before it leaves");
		assertTrue(before.stream().allMatch(a -> PEER_ID.equals(a.getPeerId())));

		Peer departed = new Peer();
		departed.id = PEER_ID;
		host.collaborationInstance().peerLeft(departed);
		pumpUiEvents();

		List<PeerAnnotation> after = peerAnnotations(openEditor);
		assertTrue(after.isEmpty(), "a departed peer must leave no annotations behind, found: " + after);
		assertNull(host.editorManager().getFollowingPeerId(), "following the departed peer must be cleared");
		assertFalse(host.editorManager().isFollowGuestSelection(), "follow-guest-selection must reset to false");
	}

	@Test
	@DisplayName("a peer switching files loses its annotations in the file it left")
	void switchingDocumentsClearsStaleAnnotations() throws Exception {
		IFile fileA = EclipseTestProjects.writeFile(hostProject, "a.txt", SEED_CONTENT);
		IFile fileB = EclipseTestProjects.writeFile(hostProject, "b.txt", SEED_CONTENT);
		openHostEditor(fileA);
		host.createRoom(hostProject, new Workspace(hostProject.getName(), new String[] { hostProject.getName() }));
		String octPathA = OctPaths.fromHostResource(fileA);
		String octPathB = OctPaths.fromHostResource(fileB);

		host.editorManager().updateTextSelection(octPathA,
				new ClientTextSelection[] { new ClientTextSelection(PEER_ID, 0, 4, false) });
		pumpUiEvents();
		assertFalse(peerAnnotations(openEditor).isEmpty(), "selection in A must render while the peer is there");

		// The peer moves to B. No empty update for A is ever sent (see class doc) —
		// this must be swept locally instead.
		host.editorManager().updateTextSelection(octPathB,
				new ClientTextSelection[] { new ClientTextSelection(PEER_ID, 0, 4, false) });
		pumpUiEvents();

		List<PeerAnnotation> remainingInA = peerAnnotations(openEditor);
		assertTrue(remainingInA.isEmpty(), "A must not keep the peer's stale annotations, found: " + remainingInA);
	}

	// ---------------------------------------------------------------

	private void openHostEditor(IFile file) throws Exception {
		AtomicReference<IEditorPart> ref = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		Display.getDefault().syncExec(() -> {
			try {
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				ref.set(IDE.openEditor(page, file, true));
			} catch (Exception e) {
				failure.set(e);
			}
		});
		if (failure.get() != null) {
			throw failure.get();
		}
		assertTrue(ref.get() instanceof ITextEditor, "expected a text editor to open on " + file);
		openEditor = (ITextEditor) ref.get();
	}

	private void closeOpenEditor() {
		if (openEditor == null) {
			return;
		}
		ITextEditor editor = openEditor;
		openEditor = null;
		try {
			Display.getDefault().syncExec(() -> editor.getSite().getPage().closeEditor(editor, false));
		} catch (Exception ignored) {
		}
	}

	/**
	 * Collects the OCT peer annotations attached to {@code editor}.
	 * {@code EditorManager} keeps them in its own {@code AnnotationModel},
	 * attached to the viewer's model as a sub-model — the parent's iterator
	 * includes attached models, so this sees them without needing the private
	 * attachment key. Mirrors {@code EditorAdoptionTest.peerAnnotations}.
	 */
	private static List<PeerAnnotation> peerAnnotations(ITextEditor editor) {
		List<PeerAnnotation> result = new ArrayList<>();
		Display.getDefault().syncExec(() -> {
			ISourceViewer viewer = sourceViewer(editor);
			if (viewer == null) {
				return;
			}
			IAnnotationModel model = viewer.getAnnotationModel();
			if (model == null) {
				return;
			}
			model.getAnnotationIterator().forEachRemaining(annotation -> {
				if (annotation instanceof PeerAnnotation peer) {
					Position position = model.getPosition((Annotation) peer);
					if (position != null) {
						result.add(peer);
					}
				}
			});
		});
		return result;
	}

	/** Same reflection fallback {@code EditorManager.getSourceViewer} uses. */
	private static ISourceViewer sourceViewer(ITextEditor editor) {
		if (!(editor instanceof AbstractTextEditor)) {
			return null;
		}
		try {
			Method method = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer");
			method.setAccessible(true);
			return (ISourceViewer) method.invoke(editor);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * {@code EditorManager} posts its annotation/seed work with
	 * {@code Display.asyncExec}. Under the PDE UI harness the test method itself
	 * runs on the SWT thread, so that work cannot run until we yield back to the
	 * event loop.
	 */
	private static void pumpUiEvents() {
		Display display = Display.getDefault();
		if (display == null || display.getThread() != Thread.currentThread()) {
			return;
		}
		for (int i = 0; i < 100 && display.readAndDispatch(); i++) {
			// drain
		}
	}
}
