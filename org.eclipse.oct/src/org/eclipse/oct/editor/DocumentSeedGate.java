/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.protocol.FileContent;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Seeds shared documents with real content and gates outgoing edits until the
 * seed is confirmed (ADR-0002).
 *
 * <p>
 * {@code awareness/openDocument} is fire-and-forget, and for non-host peers the
 * content it carries is applied to the shared Yjs document asynchronously (the
 * local replica only catches up once the seed round-trips through the OCT
 * server — see {@code CollaborationInstance.registerYjsObject} in
 * open-collaboration-service-process, which discards a guest's own {@code text}
 * argument and simply notifies the host). If a local edit is sent before that
 * sync lands, its offset is computed against an empty/stale replica; Yjs
 * silently clamps out-of-range offsets instead of rejecting them, corrupting
 * the edit for every peer.
 */
public class DocumentSeedGate {

	private static final Logger LOG = Logger.getLogger(DocumentSeedGate.class.getName());

	/** Max time to wait for an initial content seed to confirm before giving up. */
	private static final long SEED_SYNC_TIMEOUT_MS = 10_000;
	private static final long SEED_SYNC_POLL_INTERVAL_MS = 100;

	private final OCTService remoteService;
	private final Function<String, TrackedEditor> editorLookup;

	/**
	 * Paths this peer has already pushed to Yjs without a local editor being
	 * open, i.e. from {@link #seedFromDisk}. Purely a send-it-once guard for that
	 * path: a second guest opening the same path before the host does must not
	 * trigger another disk read and push.
	 *
	 * <p>
	 * It deliberately does <em>not</em> suppress {@link #seedFromEditor}'s own
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

	public DocumentSeedGate(OCTService remoteService, Function<String, TrackedEditor> editorLookup) {
		this.remoteService = remoteService;
		this.editorLookup = editorLookup;
	}

	/**
	 * Marks {@code path} as seeded without an open editor. Returns {@code true}
	 * if this call was the first to claim it.
	 */
	public boolean markSeeded(String path) {
		return seededPaths.add(path);
	}

	/**
	 * Seeds the shared document from a freshly tracked editor's buffer and gates
	 * its outgoing edits until that seed confirms.
	 */
	public void seedFromEditor(String octPath, ITextEditor textEditor, IFile file) {
		// A path already pushed by seedFromDisk still has to be announced here:
		// only openDocument tells the service process what *this editor's buffer*
		// holds (attachLocalDocument), and all the offsets it later reports are
		// computed against that string. It is also what keeps the ADR-0002 seed
		// gate on this path. Upstream ignores the redundant content itself, so
		// this is cheap rather than a full-document replace.
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
		confirmThenEnable(octPath, text);
	}

	/**
	 * Pushes {@code file}'s on-disk content as the shared document seed without
	 * opening an editor for it. Runs off the UI thread: it does I/O, and
	 * {@code openDocument} may block on the service-process pipe.
	 */
	public void seedFromDisk(IFile file, String path) {
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

	public void dispose() {
		disposed.set(true);
		seededPaths.clear();
	}

	/**
	 * {@code DocumentSyncListener} queues local edits made before its seed
	 * confirms instead of sending them (see
	 * {@link DocumentSyncListener#enableAndFlush()}) and only flushes them here,
	 * once {@code awareness/getDocumentContent} confirms this peer's own replica
	 * already holds {@code expectedContent}. Runs off the UI thread since it polls
	 * with blocking gets; if the seed still hasn't confirmed after
	 * {@link #SEED_SYNC_TIMEOUT_MS}, updates are enabled anyway (best effort) so a
	 * slow/unreachable server doesn't permanently freeze the editor's outgoing sync
	 * — logged as a warning since it means edits until it also settles may still
	 * race.
	 */
	private void confirmThenEnable(String octPath, String expectedContent) {
		String normalizedExpected = expectedContent.replace("\r\n", "\n");
		CompletableFuture.runAsync(() -> {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEED_SYNC_TIMEOUT_MS);
			do {
				if (disposed.get()) {
					return;
				}
				if (editorLookup.apply(octPath) == null) {
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
		TrackedEditor state = editorLookup.apply(octPath);
		if (state != null) {
			state.enableAndFlush();
		}
	}

	private static String readFileContent(IFile file) throws Exception {
		try (InputStream is = file.getContents()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
	}
}
