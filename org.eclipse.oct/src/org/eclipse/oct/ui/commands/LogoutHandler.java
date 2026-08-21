/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.auth.AuthenticationService;

/**
 * Command handler for logging out of OCT (clears all stored tokens).
 *
 * <p>
 * Mirrors the VS Code extension's {@code oct.signOut} command, which closes
 * the active connection before deleting stored tokens — otherwise the
 * currently hosted/joined session would keep running with credentials that
 * were just invalidated.
 */
public class LogoutHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		SessionService svc = SessionService.getInstance();
		if (svc != null) {
			// getAllInstances() already returns an immutable snapshot
			// (Map.copyOf), so it's safe to iterate while closeCurrentSession()
			// mutates the live instance map underneath.
			List<IProject> projects = new ArrayList<>(svc.getAllInstances().keySet());
			for (IProject project : projects) {
				svc.closeCurrentSession(project);
			}
		}
		AuthenticationService.getInstance().logout();
		return null;
	}

	@Override
	public boolean isEnabled() {
		return !org.eclipse.oct.prefs.OCTSettings.getInstance().getStoredUserTokens().isEmpty();
	}
}
