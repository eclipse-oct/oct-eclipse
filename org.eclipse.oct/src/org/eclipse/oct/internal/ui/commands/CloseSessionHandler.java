/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui.commands;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler for closing the current OCT session.
 */
public class CloseSessionHandler extends OctSessionHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		SessionService svc = SessionService.getInstance();
		if (svc == null) {
			return null;
		}

		// Try to find the active project with a session
		IProject project = getSessionProject(event, svc);
		if (project != null) {
			svc.closeCurrentSession(project);
		}
		return null;
	}

	private IProject getSessionProject(ExecutionEvent event, SessionService svc) {
		// First check selection
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (selection instanceof IStructuredSelection ss && !ss.isEmpty()) {
			Object first = ss.getFirstElement();
			IProject p = null;
			if (first instanceof IProject proj) {
				p = proj;
			} else if (first instanceof IResource r) {
				p = r.getProject();
			}
			if (p != null && svc.hasOpenSession(p)) {
				return p;
			}
		}

		// Fallback: find any open session
		for (IProject p : svc.getAllInstances().keySet()) {
			return p;
		}
		return null;
	}

	@Override
	protected boolean computeEnabled() {
		return hasOpenSession();
	}
}
