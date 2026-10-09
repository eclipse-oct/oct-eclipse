/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

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
				"Enter the room ID or URL:", "", JoinSessionHandler::validateRoomToken);

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

	/**
	 * A share URL is expected to carry {@code ://}; a bare room ID is a single
	 * token. Anything else with a slash in it (e.g. a filesystem path pasted
	 * by mistake) isn't either, and would otherwise be sent verbatim as the
	 * {@code room/joinRoom} path segment — a leading slash doubles up with the
	 * route's own, producing a 404 whose raw HTML body then surfaces in the
	 * error dialog.
	 */
	private static String validateRoomToken(String input) {
		if (input == null || input.isBlank()) {
			return "Room ID cannot be empty";
		}
		String trimmed = input.trim();
		if (!trimmed.contains("://") && trimmed.contains("/")) {
			return "That doesn't look like a room ID or URL";
		}
		return null;
	}
}
