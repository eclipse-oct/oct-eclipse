/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui.commands;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler for hosting an OCT collaboration session.
 */
public class HostSessionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);

		IProject project = getSelectedProject(event);
		if (project == null) {
			org.eclipse.jface.dialogs.MessageDialog.openError(window.getShell(), "Open Collaboration Tools",
					"Please select a project to share.");
			return null;
		}

		List<String> folders = new ArrayList<>();
		folders.add(project.getName());
		Workspace workspace = new Workspace(project.getName(), folders.toArray(new String[0]));

		SessionService.getInstance().createRoom(workspace, project);
		return null;
	}

	private IProject getSelectedProject(ExecutionEvent event) {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (selection instanceof IStructuredSelection ss && !ss.isEmpty()) {
			Object first = ss.getFirstElement();
			if (first instanceof IProject p) {
				return p;
			}
			if (first instanceof IResource r) {
				return r.getProject();
			}
		}
		// Fallback: active editor's project
		var page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
		if (page != null && page.getActiveEditor() != null) {
			var input = page.getActiveEditor().getEditorInput();
			IResource res = input.getAdapter(IResource.class);
			if (res != null) {
				return res.getProject();
			}
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SessionService.getInstance() != null;
	}
}
