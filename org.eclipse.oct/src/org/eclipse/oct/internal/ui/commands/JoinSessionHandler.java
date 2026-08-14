/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui.commands;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler for joining an OCT collaboration session.
 */
public class JoinSessionHandler extends OctSessionHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);

		InputDialog dialog = new InputDialog(window.getShell(), "Join Collaboration Session",
				"Enter the room ID or URL:", "",
				input -> (input == null || input.isBlank()) ? "Room ID cannot be empty" : null);

		if (dialog.open() == Window.OK) {
			String roomToken = dialog.getValue().trim();
			SessionService.getInstance().joinRoom(roomToken);
		}
		return null;
	}

	@Override
	protected boolean computeEnabled() {
		return SessionService.getInstance() != null && !hasOpenSession();
	}
}
