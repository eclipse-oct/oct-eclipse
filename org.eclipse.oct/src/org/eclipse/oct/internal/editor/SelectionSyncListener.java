/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import java.util.logging.Logger;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.rpc.OCTService;

/**
 * Listens for caret / selection changes and emits updateTextSelection RPC
 * calls.
 */
public class SelectionSyncListener implements ISelectionChangedListener {

	private static final Logger LOG = Logger.getLogger(SelectionSyncListener.class.getName());

	private final String octPath;
	private final OCTService remoteService;
	private final String selfPeerId;

	public SelectionSyncListener(String octPath, OCTService remoteService, String selfPeerId) {
		this.octPath = octPath;
		this.remoteService = remoteService;
		this.selfPeerId = selfPeerId;
	}

	@Override
	public void selectionChanged(SelectionChangedEvent event) {
		if (!(event.getSelection() instanceof ITextSelection sel)) {
			return;
		}

		int start = sel.getOffset();
		int end = sel.getOffset() + sel.getLength();
		boolean isReversed = false; // JFace selection doesn't expose direction; safe default

		ClientTextSelection selection = new ClientTextSelection(selfPeerId, start, end, isReversed);
		try {
			remoteService.updateTextSelection(octPath, new ClientTextSelection[] { selection });
		} catch (Exception e) {
			LOG.warning("Failed to send text selection for " + octPath + ": " + e.getMessage());
		}
	}
}
