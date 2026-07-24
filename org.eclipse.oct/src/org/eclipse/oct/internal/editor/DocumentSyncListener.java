/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

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
 * The sendUpdates flag prevents echo when applying remote edits.
 */
public class DocumentSyncListener implements IDocumentListener {

	private static final Logger LOG = Logger.getLogger(DocumentSyncListener.class.getName());

	private final String path;
	private final OCTService remoteService;
	private final AtomicBoolean sendUpdates;
	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	public DocumentSyncListener(String path, OCTService remoteService, AtomicBoolean sendUpdates) {
		this.path = path;
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
			return; // echo guard — skip when applying remote edits
		}

		int startOffset = event.getOffset();
		int endOffset = startOffset + event.getLength();
		String newText = event.getText() != null ? event.getText() : "";

		TextDocumentInsert insert = new TextDocumentInsert(startOffset, endOffset, newText);
		try {
			remoteService.updateDocument(path, new TextDocumentInsert[] { insert });
		} catch (Exception e) {
			LOG.warning("Failed to send document update for " + path + ": " + e.getMessage());
		}

		// Trigger re-sync if we appear to be out of sync with the host
		maybeResync(event.getDocument());
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
