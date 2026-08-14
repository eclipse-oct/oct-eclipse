/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.rpc.OCTService;

/**
 * Listens for local document changes and emits TextDocumentInsert RPC calls.
 *
 * <p>Two independent gates apply to outgoing edits:
 * <ul>
 * <li>{@code sendUpdates} (owned by {@link EditorManager}) is the echo guard:
 * it is toggled off only for the duration of applying a remote edit (or a
 * save-triggered {@code document.set(...)}) to this same {@code IDocument},
 * so that re-entrant {@code documentChanged} callback isn't mistaken for a
 * real local edit. Edits skipped for this reason are never queued — they
 * originated remotely and must not be echoed back.</li>
 * <li>{@code confirmed} (owned by this listener) reflects whether the
 * initial content seed for this document has round-tripped through the OCT
 * server yet (see {@code EditorManager.confirmSeedThenEnableUpdates}). Edits
 * made by the user before that happens are real and must not be lost, so
 * they are queued in {@link #pending} and flushed by {@link #enableAndFlush()}
 * once confirmation lands, instead of being silently dropped.</li>
 * </ul>
 */
public class DocumentSyncListener implements IDocumentListener {

	private static final Logger LOG = Logger.getLogger(DocumentSyncListener.class.getName());

	private final String octPath;
	private final OCTService remoteService;
	private final AtomicBoolean sendUpdates;
	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	/** Guards {@link #confirmed} and {@link #pending} so enabling and flushing is atomic. */
	private final Object pendingLock = new Object();
	private final AtomicBoolean confirmed = new AtomicBoolean(false);
	private final List<TextDocumentInsert> pending = new ArrayList<>();

	public DocumentSyncListener(String octPath, OCTService remoteService, AtomicBoolean sendUpdates) {
		this.octPath = octPath;
		this.remoteService = remoteService;
		this.sendUpdates = sendUpdates;
	}

	@Override
	public void documentAboutToBeChanged(DocumentEvent event) {
		// nothing needed
	}

	@Override
	public void documentChanged(DocumentEvent event) {
		if (!sendUpdates.get()) {
			return; // echo guard — skip when applying remote edits, never queued
		}

		int startOffset = event.getOffset();
		int endOffset = startOffset + event.getLength();
		String newText = event.getText() != null ? event.getText() : "";
		TextDocumentInsert insert = new TextDocumentInsert(startOffset, endOffset, newText);

		synchronized (pendingLock) {
			if (!confirmed.get()) {
				// Seed hasn't confirmed yet — queue rather than drop. Offsets remain
				// valid once flushed: the local buffer (and thus every subsequent
				// event's offsets) evolves from the exact same content that was seeded,
				// regardless of when the seed round-trip happens to land.
				pending.add(insert);
				return;
			}
		}
		sendInsert(insert);

		// Trigger re-sync if we appear to be out of sync with the host
		maybeResync(event.getDocument());
	}

	/**
	 * Marks the initial seed as confirmed and flushes any edits queued while it
	 * was pending, atomically with respect to {@link #documentChanged}. Called by
	 * {@link EditorManager} once {@code awareness/getDocumentContent} confirms
	 * this peer's replica holds the expected seeded content.
	 */
	public void enableAndFlush() {
		List<TextDocumentInsert> toSend;
		synchronized (pendingLock) {
			confirmed.set(true);
			if (pending.isEmpty()) {
				return;
			}
			toSend = new ArrayList<>(pending);
			pending.clear();
		}
		try {
			remoteService.updateDocument(octPath, toSend.toArray(new TextDocumentInsert[0]));
		} catch (Exception e) {
			LOG.warning(
					"Failed to flush " + toSend.size() + " pending document update(s) for " + octPath + ": " + e.getMessage());
		}
	}

	private void sendInsert(TextDocumentInsert insert) {
		try {
			remoteService.updateDocument(octPath, new TextDocumentInsert[] { insert });
		} catch (Exception e) {
			LOG.warning("Failed to send document update for " + octPath + ": " + e.getMessage());
		}
	}

	private void maybeResync(IDocument document) {
		if (isSyncing.get()) {
			return;
			// Full resync via getDocumentContent is only triggered when we suspect drift.
			// For now this is a no-op placeholder — full sync is handled by the host
			// broadcasting the canonical content when an editor is opened.
		}
	}
}
