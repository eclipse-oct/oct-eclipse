/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.eclipse.oct.tests.support.TestSync.awaitDocumentContent;
import static org.eclipse.oct.tests.support.TestSync.awaitDocumentUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.oct.editor.PeerAnnotation;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.protocol.SessionData;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestOCTMessageHandler;
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
 * Regression tests for the "host already had the file open before the session
 * started" case.
 *
 * <p>
 * {@link EditorManagerSyncTest} opens its editor <em>after</em> the session is
 * established, so it only ever exercises {@code IPartListener2.partOpened}.
 * That listener never fires for a tab that was already open, and
 * {@code partActivated} bails when no {@code EditorState} exists yet — so such
 * a file used to get no {@code DocumentSyncListener}, no
 * {@code SelectionSyncListener}, no peer annotation model, and no
 * {@code awareness/openDocument} seed for the entire session. The visible
 * symptoms were: peer cursors never appeared, local typing was never
 * broadcast, incoming peer edits were dropped, and the file only ever
 * converged when somebody saved (which travels the unrelated
 * {@code fileSystem/writeFile} path).
 *
 * <p>
 * The ordering in {@link #establishSession()} is therefore load-bearing: the
 * editor is opened <em>before</em> {@code createRoom}, because
 * {@code TestPeer.createRoom} constructs the {@code EditorManager} only after
 * the room exists — mirroring production
 * {@code SessionService.sessionCreated}.
 */
@TestInstance(Lifecycle.PER_CLASS)
class EditorAdoptionTest {

	private static final String SEED_CONTENT = "HELLO WORLD!";
	private static final String UNOPENED_CONTENT = "NOT OPEN ON THE HOST";

	private String serverUrl;
	private TestPeer host;
	private TestPeer guest;
	private IProject hostProject;
	private IFile hostFile;
	private String octPath;
	private String unopenedOctPath;
	private ITextEditor openEditor;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("editor-adoption-host");
		hostFile = EclipseTestProjects.writeFile(hostProject, "test.txt", SEED_CONTENT);
		octPath = OctPaths.fromHostResource(hostFile);
		unopenedOctPath = OctPaths
				.fromHostResource(EclipseTestProjects.writeFile(hostProject, "unopened.txt", UNOPENED_CONTENT));

		// The whole point of this suite: the editor exists before any OCT session
		// (and therefore before any EditorManager) does.
		openHostEditor();

		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		guest = new TestPeer(serverUrl, "guest", TestPeer.Role.GUEST);

		SessionData hostSession = host.createRoom(hostProject,
				new Workspace(hostProject.getName(), new String[] { hostProject.getName() }));
		guest.joinRoom(hostSession.roomId);
		guest.awaitInit(30, TimeUnit.SECONDS);

		// Same as EditorManagerSyncTest: TestPeer deliberately wires no guest-side
		// EditorManager, so register the guest's interest in these paths by hand or
		// its service process never creates the wrapper that pushes
		// awareness/updateDocument notifications to it.
		//
		// unopened.txt is genuinely unshared at this point, so "" is accurate here.
		guest.octService().openDocument("text", unopenedOctPath, "");

		// octPath needs more care. Unlike EditorManagerSyncTest — where the host's
		// editor opens *after* this point, so the shared doc is still empty — the
		// host here already seeded octPath during createRoom(). Upstream
		// YjsNormalizedTextDocument.attachLocalDocument compares what we declare
		// against the local *replica* of the shared Y.Text, and a freshly joined
		// guest's replica is still empty for a beat. Declaring the real content
		// against an empty replica marks it diverged, and a diverged document
		// answers every subsequent change with a whole-document reconcile rather
		// than a delta — so the offsets asserted below would never reach the wire.
		//
		// Declare once to register interest, wait for the replica to actually carry
		// the host's seed (getDocumentContent returns the Y.Text once the path is
		// registered, so this really does wait for Yjs sync rather than falling back
		// to a host file read), then declare again so attachLocalDocument compares
		// against the settled content and clears the diverged flag.
		guest.octService().openDocument("text", octPath, SEED_CONTENT);
		awaitDocumentContent(guest, octPath, SEED_CONTENT);
		guest.octService().openDocument("text", octPath, SEED_CONTENT);
	}

	@AfterEach
	void closeSession() {
		closeOpenEditor();
		if (guest != null) {
			guest.close();
		}
		if (host != null) {
			host.close();
		}
		EclipseTestProjects.deleteProject(hostProject);
	}

	@Test
	@DisplayName("an editor already open when the session starts is adopted and seeds the shared document")
	void editorOpenedBeforeSessionIsAdopted() throws Exception {
		// Nothing opens an editor during the session here — if adoptOpenEditors()
		// did not sweep the already-open tab, no openDocument is ever sent for this
		// path and the replicas stay empty.
		awaitDocumentContent(host, octPath, SEED_CONTENT);
		awaitDocumentContent(guest, octPath, SEED_CONTENT);
	}

	@Test
	@DisplayName("typing in an adopted editor reaches the guest live, with the correct offset")
	void adoptedEditorEditsPropagateToGuest() throws Exception {
		IDocument document = editHostDocument(5, 0, " NEW");

		// Before the adoption fix this timed out: with no DocumentSyncListener
		// attached, the keystroke was never sent at all and the guest only ever
		// saw this content if the host saved the file.
		// Asserting on the offset, not just on eventual convergence: a whole-document
		// reconcile would also converge the content, but would mean the delta path
		// (and with it the offset translation) is not actually working.
		TestOCTMessageHandler.DocumentUpdateEvent received = awaitDocumentUpdate(guest,
				event -> event.updates() != null && event.updates().length > 0 && event.updates()[0].startOffset == 5);
		assertNotNull(received, "guest must receive the adopted editor's live edit");
		assertEquals(octPath, received.path(), "the real EditorManager-computed octPath must be used on the wire");
		assertEquals(5, received.updates()[0].startOffset, "start offset must survive the wire unchanged");

		awaitDocumentContent(guest, octPath, document.get());
	}

	/**
	 * Pins the follow-off contract of {@code guestOpenedEditor}: seed the path,
	 * but do not open a tab in the host's workbench.
	 *
	 * <p>
	 * Note on what this can and cannot prove: upstream {@code editor.onOpen} also
	 * seeds an unshared path from the host's disk via {@code readOwnFile}, so the
	 * <em>content</em> assertion alone would pass even with no Eclipse-side seed
	 * at all. The regression this really guards is the tab assertion plus the fact
	 * that {@code guestOpenedEditor} no longer marks a path in {@code seededPaths}
	 * without pushing anything — that half is covered by
	 * {@link #editorOpenedBeforeSessionIsAdopted()}, which would break if a
	 * bogus {@code seededPaths} entry suppressed the real seed.
	 */
	@Test
	@DisplayName("with follow off, a guest opening a file the host has no editor for seeds it but opens no tab")
	void guestOpenedPathIsSeededWithoutOpeningAnEditor() throws Exception {
		assertFalse(host.editorManager().isFollowGuestSelection(),
				"this test covers the follow-off path, which must still seed");

		host.editorManager().guestOpenedEditor(unopenedOctPath);
		pumpUiEvents();

		awaitDocumentContent(guest, unopenedOctPath, UNOPENED_CONTENT);
		assertFalse(isEditorOpen(unopenedOctPath),
				"follow is off, so the guest's open must not add a tab to the host's workbench");
	}

	@Test
	@DisplayName("peer selections render even when follow is off")
	void guestSelectionRendersWhenFollowIsOff() throws Exception {
		assertFalse(host.editorManager().isFollowGuestSelection(), "precondition: follow must be off");

		host.editorManager().updateTextSelection(octPath,
				new ClientTextSelection[] { new ClientTextSelection("peer-1", 2, 6, false) });
		pumpUiEvents();

		List<PeerAnnotation> annotations = peerAnnotations(openEditor);
		assertTrue(annotations.stream().anyMatch(a -> PeerAnnotation.TYPE_CURSOR.equals(a.getType())),
				"a peer cursor annotation must be rendered with follow off, found: " + annotations);
		assertTrue(annotations.stream().anyMatch(a -> PeerAnnotation.TYPE_SELECTION.equals(a.getType())),
				"a peer selection annotation must be rendered with follow off, found: " + annotations);
		assertTrue(annotations.stream().allMatch(a -> "peer-1".equals(a.getPeerId())),
				"annotations must be attributed to the reporting peer");
	}

	// ---------------------------------------------------------------

	private void openHostEditor() throws Exception {
		AtomicReference<IEditorPart> ref = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		Display.getDefault().syncExec(() -> {
			try {
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				ref.set(IDE.openEditor(page, hostFile, true));
			} catch (Exception e) {
				failure.set(e);
			}
		});
		if (failure.get() != null) {
			throw failure.get();
		}
		assertTrue(ref.get() instanceof ITextEditor, "expected a text editor to open on " + hostFile);
		openEditor = (ITextEditor) ref.get();
	}

	private IDocument editHostDocument(int offset, int length, String text) throws Exception {
		AtomicReference<IDocument> ref = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		Display.getDefault().syncExec(() -> {
			try {
				IDocument document = openEditor.getDocumentProvider().getDocument(openEditor.getEditorInput());
				document.replace(offset, length, text);
				ref.set(document);
			} catch (Exception e) {
				failure.set(e);
			}
		});
		if (failure.get() != null) {
			throw failure.get();
		}
		return ref.get();
	}

	private boolean isEditorOpen(String protocolPath) {
		AtomicReference<Boolean> found = new AtomicReference<>(Boolean.FALSE);
		Display.getDefault().syncExec(() -> {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			for (var ref : page.getEditorReferences()) {
				IEditorPart part = ref.getEditor(false);
				if (part == null) {
					continue;
				}
				IFile file = part.getEditorInput().getAdapter(IFile.class);
				if (file != null && OctPaths.referToSameDocument(OctPaths.fromHostResource(file), protocolPath)) {
					found.set(Boolean.TRUE);
					return;
				}
			}
		});
		return found.get();
	}

	/**
	 * Collects the OCT peer annotations attached to {@code editor}.
	 * {@code EditorManager} keeps them in its own {@code AnnotationModel},
	 * attached to the viewer's model as a sub-model — the parent's iterator
	 * includes attached models, so this sees them without needing the private
	 * attachment key.
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
					assertNotNull(position, "a rendered peer annotation must have a position");
					result.add(peer);
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
	 * event loop. Bounded rather than drain-until-empty: workbench redraws can
	 * keep {@code readAndDispatch()} returning true indefinitely.
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
}
