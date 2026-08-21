/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.eclipse.oct.tests.support.TestSync.awaitDocumentContent;
import static org.eclipse.oct.tests.support.TestSync.awaitDocumentUpdate;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.IDocument;
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
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * End-to-end regression test for the real {@code EditorManager} wiring.
 *
 * <p>{@link AwarenessSmokeTest} and {@link HandshakeTest} drive
 * {@code octService()} RPCs directly and never touch {@code EditorManager},
 * {@code DocumentSyncListener}, or the seed-sync gating fixed in
 * {@code EditorManager.confirmSeedThenEnableUpdates} — so a regression there
 * (e.g. {@code sendUpdates} defaulting back to {@code true}, or the seed
 * confirmation loop never firing) would go completely unnoticed by them.
 *
 * <p>This class opens a real {@link ITextEditor} on the host's shared file,
 * backed by the real PDE UI harness workbench (see {@code useUIHarness=true}
 * in {@code pom.xml}), so {@code IPartListener2.partOpened},
 * {@code DocumentSyncListener.documentChanged}, and the production
 * seed-confirmation flow all run for real — exactly like when a user opens a
 * file in the actual plugin — instead of being simulated via raw RPC calls.
 */
@TestInstance(Lifecycle.PER_CLASS)
class EditorManagerSyncTest {

	private static final String SEED_CONTENT = "HELLO WORLD!";

	private String serverUrl;
	private TestPeer host;
	private TestPeer guest;
	private IProject hostProject;
	private IFile hostFile;
	private String octPath;
	private ITextEditor openEditor;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("editor-sync-host");
		hostFile = EclipseTestProjects.writeFile(hostProject, "test.txt", SEED_CONTENT);
		octPath = OctPaths.fromHostResource(hostFile);

		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		guest = new TestPeer(serverUrl, "guest", TestPeer.Role.GUEST);

		SessionData hostSession = host.createRoom(hostProject,
				new Workspace(hostProject.getName(), new String[] { hostProject.getName() }));
		guest.joinRoom(hostSession.roomId);
		guest.awaitInit(30, TimeUnit.SECONDS);

		// In the real plugin, guests get their own EditorManager too (see
		// SessionService.sessionCreated(..., isHost=false)), and its
		// partOpened()/openDocument() call is what makes the *guest's own*
		// service-process instance lazily create the YjsNormalizedTextDocument
		// wrapper that pushes awareness/updateDocument notifications to it (see
		// CollaborationInstance.getNormalizedDocument in
		// open-collaboration-service-process). Since TestPeer deliberately skips
		// wiring a guest-side EditorManager/CollaborationInstance for this suite
		// (see its class doc), simulate that same registration directly — without
		// it, the guest can still *pull* content via getDocumentContent (as
		// openingHostEditorSeedsSharedDocument does below) but would never
		// receive a single awareness/updateDocument push, hanging
		// firstDocumentUpdate() forever regardless of what the host sends.
		guest.octService().openDocument("text", octPath, "");
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
	@DisplayName("opening a real editor on the host seeds the shared document with its actual file content")
	void openingHostEditorSeedsSharedDocument() throws Exception {
		openHostEditor();

		// Both peers' Yjs replicas must converge on the real file content that
		// EditorManager.partOpened() read off disk and sent via openDocument() —
		// not an empty document (see the bug this guarded against: a document
		// provider that hadn't finished loading yet).
		awaitDocumentContent(guest, octPath, SEED_CONTENT);
		awaitDocumentContent(host, octPath, SEED_CONTENT);
	}

	@Test
	@DisplayName("typing into the host's real editor immediately after opening still reaches the guest with the "
			+ "correct offset (EditorManager seed-sync gating regression test)")
	void hostEditorEditsPropagateToGuestWithCorrectOffset() throws Exception {
		openHostEditor();

		// Exercises EditorManager.confirmSeedThenEnableUpdates end-to-end:
		// DocumentSyncListener must not drop or corrupt this edit even though we
		// deliberately type immediately, without waiting for the seed to confirm.
		// Before the DocumentSyncListener queue-and-flush fix, an edit landing in
		// this window was silently swallowed (sendUpdates was still false, so
		// documentChanged() just returned) — this test would then time out
		// waiting for a matching update. Here the *real* EditorManager is
		// responsible for gating/queueing, not test code calling
		// awaitDocumentContent first.
		IDocument document = editHostDocument(5, 0, " NEW");

		// Look for an update starting at offset 5 specifically, not just the
		// first one the guest ever receives: because establishSession() makes
		// the guest watch octPath upfront, it also observes the *content seed*
		// (offset 0) that partOpened() sends before this edit — firstDocumentUpdate()
		// would spuriously match that instead of the edit under test.
		TestOCTMessageHandler.DocumentUpdateEvent received = awaitDocumentUpdate(guest,
				event -> event.updates() != null && event.updates().length > 0 && event.updates()[0].startOffset == 5);
		assertNotNull(received, "guest must receive the host editor's live edit");
		assertEquals(octPath, received.path(), "the real EditorManager-computed octPath must be used on the wire");
		assertTrue(received.updates().length >= 1, "at least one insert was expected");
		assertArrayEquals(new int[] { 5 }, new int[] { received.updates()[0].startOffset },
				"start offset of the real editor's edit must be preserved on the wire");

		// Cross-check the guest's own replica actually converged on the edited
		// text, not just that a (possibly stale/corrupted) notification arrived.
		awaitDocumentContent(guest, octPath, document.get());
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
