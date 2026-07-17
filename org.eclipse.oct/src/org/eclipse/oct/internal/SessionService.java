/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.oct.internal.auth.AuthenticationService;
import org.eclipse.oct.internal.fs.WorkspaceFileSystemService;
import org.eclipse.oct.internal.prefs.OCTSettings;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.oct.internal.rpc.FileSystemMessageHandler;
import org.eclipse.oct.internal.rpc.OCTMessageHandler;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.internal.rpc.ServiceProcess;
import org.eclipse.oct.internal.util.EventEmitter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Central session lifecycle manager.
 */
public class SessionService {

	private static final Logger LOG = Logger.getLogger(SessionService.class.getName());
	private static SessionService INSTANCE;

	private final Map<IProject, ServiceProcess> processes = new HashMap<>();
	private final Map<IProject, CollaborationInstance> instances = new HashMap<>();
	private final List<IProject> tempProjectsToDelete = new ArrayList<>();

	public final EventEmitter<CollaborationInstance> onSessionCreated = new EventEmitter<>();
	public final EventEmitter<IProject> onSessionClosed = new EventEmitter<>();

	public static SessionService getInstance() {
		return INSTANCE;
	}

	public static void setInstance(SessionService instance) {
		INSTANCE = instance;
	}

	public boolean hasOpenSession(IProject project) {
		return instances.containsKey(project);
	}

	public CollaborationInstance getCollaborationInstance(IProject project) {
		return instances.get(project);
	}

	public Map<IProject, CollaborationInstance> getAllInstances() {
		return Map.copyOf(instances);
	}

	public void createRoom(Workspace workspace, IProject project) {
		if (instances.containsKey(project))
			return;

		String serverUrl = OCTSettings.getInstance().getDefaultServerURL();
		ServiceProcess process = createServiceProcess(serverUrl);
		processes.put(project, process);

		OCTService octService = process.getOctService();
		CompletableFuture<SessionData> future = octService.createRoom(workspace);

		Job job = new Job("Creating OCT room...") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Creating room", IProgressMonitor.UNKNOWN);
				try {
					SessionData sessionData = future.get(30, TimeUnit.SECONDS);
					if (sessionData != null) {
						sessionCreated(sessionData, serverUrl, project, monitor, true);
						Display.getDefault().asyncExec(() -> {
							String msg = "Hosted session created!\nRoom ID: " + sessionData.roomId
									+ "\n\nShare this ID with collaborators.";
							System.out.println("SessionService.createRoom(...).new Job() {...}.run(): " + sessionData.roomId);
							MessageDialog.openInformation(
									PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
									"Open Collaboration Tools", msg);
						});
					}
				} catch (Exception e) {
					LOG.log(Level.SEVERE, "Error creating room", e);
					Display.getDefault()
							.asyncExec(() -> MessageDialog.openError(
									PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), "OCT Error",
									"Failed to create room: " + e.getMessage()));
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	public void joinRoom(String roomToken) {
		final AtomicReference<String> serverUrl = new AtomicReference<>();
		serverUrl.set(OCTSettings.getInstance().getDefaultServerURL());

		// Support pasting a full room URL (format: serverUrl#roomId)
		if (roomToken.contains("://")) {
			try {
				java.net.URI uri = new java.net.URI(roomToken);
				String fragment = uri.getFragment();
				if (fragment != null && !fragment.isBlank()) {
					serverUrl.set(new java.net.URI(uri.getScheme(), uri.getAuthority(), "", null, null).toString());
					roomToken = fragment;
				}
			} catch (java.net.URISyntaxException ignored) {
				// Not a valid URL, use token as-is
			}
		}

		ServiceProcess process = createServiceProcess(serverUrl.get());
		OCTService octService = process.getOctService();

		CompletableFuture<SessionData> future = octService.joinRoom(roomToken);

		Job job = new Job("Joining OCT room...") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Joining room", IProgressMonitor.UNKNOWN);
				try {
					SessionData sessionData = future.get(30, TimeUnit.SECONDS);
					if (sessionData != null) {
						IProject tempProject = createTempProject(sessionData.workspace.name, monitor);
						if (tempProject != null) {
							processes.put(tempProject, process);
							tempProjectsToDelete.add(tempProject);
							sessionCreated(sessionData, serverUrl.get(), tempProject, monitor, false);
						}
					}
				} catch (Exception e) {
					LOG.log(Level.SEVERE, "Error joining room", e);
					Display.getDefault()
							.asyncExec(() -> MessageDialog.openError(
									PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), "OCT Error",
									"Failed to join room: " + e.getMessage()));
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	public void closeCurrentSession(IProject project) {
		try {
			ServiceProcess process = processes.get(project);
			if (process != null) {
				OCTService svc = process.getOctService();
				svc.closeSession().get(5, TimeUnit.SECONDS);
			}
		} catch (Exception e) {
			LOG.warning("Error closing session: " + e.getMessage());
		}

		ServiceProcess process = processes.remove(project);
		if (process != null)
			process.close();

		CollaborationInstance instance = instances.remove(project);
		if (instance != null)
			instance.dispose();

		onSessionClosed.fire(project);
	}

	public void projectClosed(IProject project) {
		if (tempProjectsToDelete.contains(project)) {
			tempProjectsToDelete.remove(project);
			try {
				project.delete(true, true, new NullProgressMonitor());
			} catch (CoreException e) {
				LOG.warning("Failed to delete temp project: " + e.getMessage());
			}
		}
	}

	private void sessionCreated(SessionData sessionData, String serverUrl, IProject project, IProgressMonitor monitor, boolean isHost) {
		if (sessionData.authToken != null) {
			AuthenticationService.getInstance().onAuthenticated(sessionData.authToken, serverUrl);
		}

		ServiceProcess process = processes.get(project);
		if (process == null)
			throw new IllegalStateException("No process found for project: " + project.getName());

		WorkspaceFileSystemService wfs = new WorkspaceFileSystemService(project);
		CollaborationInstance instance = new CollaborationInstance(process.getOctService(), project, sessionData,
				isHost);
		instance.setWorkspaceFileSystem(wfs);

		// Wire EditorManager
		org.eclipse.oct.internal.editor.EditorManager em = new org.eclipse.oct.internal.editor.EditorManager(
				(org.eclipse.oct.internal.rpc.OCTService) process.getOctService(), project);
		instance.setEditorManager(em);

		instances.put(project, instance);
		onSessionCreated.fire(instance);
	}

	private ServiceProcess createServiceProcess(String serverUrl) {
		OCTMessageHandler octHandler = new OCTMessageHandler(serverUrl, onSessionCreated);
		FileSystemMessageHandler fsHandler = new FileSystemMessageHandler(serverUrl, onSessionCreated);
		return new ServiceProcess(serverUrl, List.of(fsHandler, octHandler));
	}

	private IProject createTempProject(String name, IProgressMonitor monitor) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String projectName = name + "-oct-" + System.currentTimeMillis();
		IProject project = workspace.getRoot().getProject(projectName);
		try {
			IProjectDescription desc = workspace.newProjectDescription(projectName);
			project.create(desc, monitor);
			project.open(monitor);
			return project;
		} catch (CoreException e) {
			LOG.log(Level.SEVERE, "Failed to create temp project", e);
			return null;
		}
	}
}
