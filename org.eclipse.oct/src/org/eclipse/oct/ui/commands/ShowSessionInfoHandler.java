/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.ui.SessionCreatedDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler that re-opens the "Session Created" dialog for the active
 * collaboration session, allowing the user to copy the room ID or invitation
 * URL to the clipboard again.
 */
public class ShowSessionInfoHandler extends OctSessionHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		SessionService svc = SessionService.getInstance();
		if (svc == null) {
			return null;
		}

		CollaborationInstance instance = svc.getAllInstances().values().stream().findFirst().orElse(null);
		if (instance == null) {
			return null;
		}

		String roomId = instance.sessionData.roomId;

		new SessionCreatedDialog(HandlerUtil.getActiveShellChecked(event), roomId, instance.serverUrl).open();
		return null;
	}

	@Override
	protected boolean computeEnabled() {
		return hasOpenSession();
	}
}
