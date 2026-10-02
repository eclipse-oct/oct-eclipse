/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.oct.editor.EditorManager;
import org.eclipse.oct.internal.auth.AuthenticationService;
import org.eclipse.oct.internal.fs.WorkspaceChangeListener;
import org.eclipse.oct.internal.fs.WorkspaceFileSystemService;
import org.eclipse.oct.internal.rpc.FileSystemMessageHandler;
import org.eclipse.oct.internal.rpc.OCTMessageHandler;
import org.eclipse.oct.internal.rpc.OCTService;
import org.eclipse.oct.internal.rpc.ServiceProcess;
import org.eclipse.oct.prefs.OCTSettings;
import org.eclipse.oct.protocol.SessionData;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.ui.SessionCreatedDialog;
import org.eclipse.oct.util.EventEmitter;
import org.eclipse.oct.util.UIThread;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressConstants;

/**
 * Central session lifecycle manager.
 */
public class SessionService {

	private static final Logger LOG = Logger.getLogger(SessionService.class.getName());
	private static SessionService INSTANCE;

	/** Safety bound for {@link #awaitSession}: long enough that a human typing
	 * credentials into a browser never trips it, short enough that a wedged
	 * {@code oct-service-process} cannot pin a job forever. */
	private static final long AWAIT_SESSION_SAFETY_BOUND_MS = TimeUnit.MINUTES.toMillis(5);

	private final Map<IProject, ServiceProcess> processes = new HashMap<>();
	private final Map<IProject, CollaborationInstance> instances = new HashMap<>();
	private final List<IProject> tempProjectsToDelete = new ArrayList<>();
	private final Set<IProject> pendingRoomCreations = new HashSet<>();
	private final Set<String> pendingRoomJoins = new HashSet<>();

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

	/**
	 * Whether a {@link ServiceProcess} is still registered for {@code project}.
	 * A cancelled/aborted/failed connect attempt must leave no entry behind —
	 * see {@code ConnectCancellationTest}.
	 */
	public boolean hasProcess(IProject project) {
		return processes.containsKey(project);
	}

	public CollaborationInstance getCollaborationInstance(IProject project) {
		return instances.get(project);
	}

	/**
	 * Associate an already-wired instance with a project so
	 * {@link WorkspaceChangeListener} can find it.
	 * Production {@link #sessionCreated} does this; tests that build a
	 * {@link CollaborationInstance} via {@code TestPeer} must call this or
	 * host saves never propagate.
	 */
	public void registerInstance(IProject project, CollaborationInstance instance) {
		instances.put(project, instance);
	}

	public void unregisterInstance(IProject project) {
		instances.remove(project);
	}

	public Map<IProject, CollaborationInstance> getAllInstances() {
		return Map.copyOf(instances);
	}

	public void createRoom(Workspace workspace, IProject project) {
		if (instances.containsKey(project) || !pendingRoomCreations.add(project)) {
			// Either a session is already open, or a creation request for this
			// project is already in flight (e.g. the user double-clicked "Host
			// Session" before the async room creation completed).
			return;
		}

		String serverUrl = OCTSettings.getInstance().getDefaultServerURL();
		CompletableFuture<SessionData> future;
		try {
			ServiceProcess process = createServiceProcess(serverUrl);
			ServiceProcess displaced = processes.put(project, process);
			if (displaced != null) {
				// A prior attempt for this project left its process behind (should not
				// happen given the finally-block teardown below, but a retry must not
				// silently orphan it if it ever does).
				displaced.close();
			}
			OCTService octService = process.getOctService();
			future = octService.createRoom(workspace);
		} catch (Exception e) {
			// No job is scheduled yet, so its finally-block teardown never runs.
			// Releasing the in-flight guard here is what keeps a failure
			// recoverable: left set, the guard clause above drops every later
			// attempt for this project without a word until Eclipse restarts.
			pendingRoomCreations.remove(project);
			ServiceProcess leftover = processes.remove(project);
			if (leftover != null) {
				leftover.close();
			}
			reportConnectFailure("Failed to create room.", e, serverUrl);
			return;
		}

		Job job = new Job("Creating OCT room...") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Creating room", IProgressMonitor.UNKNOWN);
				Runnable unsubscribeAbort = AuthenticationService.getInstance().onAuthAborted.onEvent(abortedUrl -> {
					if (serverUrl.equals(abortedUrl)) {
						future.cancel(true);
					}
				});
				boolean sessionEstablished = false;
				try {
					SessionData sessionData = awaitSession(future, monitor);
					if (sessionData == null) {
						return Status.CANCEL_STATUS;
					}
					sessionCreated(sessionData, serverUrl, project, monitor, true);
					sessionEstablished = true;
					UIThread.asyncExec(() -> {
						new SessionCreatedDialog(activeShellOrNull(), sessionData.roomId, serverUrl).open();
					});
				} catch (Exception e) {
					return Status.error(reportConnectFailure("Failed to create room.", e, serverUrl), e);
				} finally {
					unsubscribeAbort.run();
					pendingRoomCreations.remove(project);
					if (!sessionEstablished) {
						ServiceProcess leftover = processes.remove(project);
						if (leftover != null) {
							leftover.close();
						}
					}
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		// The catch block reports failures itself; without this the platform's
		// job-error handling opens a second error dialog on top of that one.
		job.setProperty(IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY, Boolean.TRUE);
		job.schedule();
	}

	public void joinRoom(String roomToken) {
		final AtomicReference<String> serverUrl = new AtomicReference<>();
		serverUrl.set(OCTSettings.getInstance().getDefaultServerURL());

		// Support pasting a full room URL (format: serverUrl#roomId)
		if (roomToken.contains("://")) {
			try {
				URI uri = new URI(roomToken);
				String fragment = uri.getFragment();
				if (fragment != null && !fragment.isBlank()) {
					String parsed = new URI(uri.getScheme(), uri.getAuthority(), "", null, null).toString();
					serverUrl.set(OCTSettings.normalizeServerUrl(parsed));
					roomToken = fragment;
				}
			} catch (URISyntaxException ignored) {
				// Not a valid URL, use token as-is
			}
		}

		String joinKey = serverUrl.get() + "#" + roomToken;
		if (!pendingRoomJoins.add(joinKey)) {
			// A join request for this exact room is already in flight (e.g. a
			// double-click before the async join completed).
			return;
		}

		ServiceProcess process;
		CompletableFuture<SessionData> future;
		try {
			process = createServiceProcess(serverUrl.get());
			OCTService octService = process.getOctService();
			future = octService.joinRoom(roomToken);
		} catch (Exception e) {
			// See createRoom: the guard has to be released here, because the job
			// whose finally block would otherwise do it never gets scheduled.
			pendingRoomJoins.remove(joinKey);
			reportConnectFailure("Failed to join room.", e, serverUrl.get());
			return;
		}

		Job job = new Job("Joining OCT room...") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Joining room", IProgressMonitor.UNKNOWN);
				Runnable unsubscribeAbort = AuthenticationService.getInstance().onAuthAborted.onEvent(abortedUrl -> {
					if (serverUrl.get().equals(abortedUrl)) {
						future.cancel(true);
					}
				});
				boolean sessionEstablished = false;
				try {
					SessionData sessionData = awaitSession(future, monitor);
					if (sessionData == null) {
						return Status.CANCEL_STATUS;
					}
					IProject tempProject = createTempProject(sessionData.workspace.name, monitor);
					if (tempProject != null) {
						processes.put(tempProject, process);
						tempProjectsToDelete.add(tempProject);
						sessionCreated(sessionData, serverUrl.get(), tempProject, monitor, false);
						sessionEstablished = true;
					}
				} catch (Exception e) {
					return Status.error(reportConnectFailure("Failed to join room.", e, serverUrl.get()), e);
				} finally {
					unsubscribeAbort.run();
					pendingRoomJoins.remove(joinKey);
					if (!sessionEstablished) {
						process.close();
					}
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		// The catch block reports failures itself; without this the platform's
		// job-error handling opens a second error dialog on top of that one.
		job.setProperty(IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY, Boolean.TRUE);
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
		if (process != null) {
			process.close();
		}

		CollaborationInstance instance = instances.remove(project);
		if (instance != null) {
			instance.dispose();
		}

		onSessionClosed.fire(project);
		if (tempProjectsToDelete.remove(project)) {
			deleteTempProject(project);
		}
	}

	private void deleteTempProject(IProject project) {
		WorkspaceJob job = new WorkspaceJob("Deleting Project: " + project.getName()) {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				if (project.exists()) {
					project.delete(true, true, monitor);
				}
				return Status.OK_STATUS;
			}
		};
		job.setRule(project);
		job.setUser(false);
		job.schedule();
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

	private void sessionCreated(SessionData sessionData, String serverUrl, IProject project, IProgressMonitor monitor,
			boolean isHost) {
		if (sessionData.authToken != null) {
			AuthenticationService.getInstance().onAuthenticated(sessionData.authToken, serverUrl);
		}

		ServiceProcess process = processes.get(project);
		if (process == null) {
			throw new IllegalStateException("No process found for project: " + project.getName());
		}

		WorkspaceFileSystemService wfs = new WorkspaceFileSystemService(project);
		CollaborationInstance instance = new CollaborationInstance(process.getOctService(), project, sessionData,
				isHost, serverUrl);
		instance.setWorkspaceFileSystem(wfs);

		// Wire EditorManager
		EditorManager em = new EditorManager((OCTService) process.getOctService(), project, isHost, instance.peerColors);
		em.setPeerNameLookup(instance::peerDisplayName);
		instance.setEditorManager(em);

		instances.put(project, instance);
		onSessionCreated.fire(instance);
	}

	/**
	 * Blocks the given job's worker thread until {@code f} completes, is
	 * cancelled (by the user pressing Cancel on the job's progress, or by an
	 * {@link AuthenticationService#onAuthAborted} match), or a generous safety
	 * bound elapses. Returns {@code null} on cancellation so the caller can
	 * distinguish "user aborted" from "server responded" without inspecting
	 * {@link CompletableFuture#isCancelled()} itself.
	 *
	 * <p>
	 * Deliberately has no fixed short deadline: waiting on a human to
	 * complete a browser login legitimately takes longer than a network
	 * round-trip. The safety bound only guards against a wedged
	 * {@code oct-service-process} pinning the job forever.
	 */
	private SessionData awaitSession(CompletableFuture<SessionData> f, IProgressMonitor monitor) throws Exception {
		long deadline = System.currentTimeMillis() + AWAIT_SESSION_SAFETY_BOUND_MS;
		while (!f.isDone()) {
			if (monitor.isCanceled()) {
				f.cancel(true);
				return null;
			}
			if (System.currentTimeMillis() > deadline) {
				f.cancel(true);
				throw new TimeoutException(
						"Server did not respond within " + TimeUnit.MILLISECONDS.toMinutes(AWAIT_SESSION_SAFETY_BOUND_MS)
								+ " minutes");
			}
			Thread.sleep(100);
		}
		if (f.isCancelled()) {
			// Cancelled out-of-band, e.g. by a matching onAuthAborted — f.get() would
			// throw CancellationException, which callers would have to special-case.
			return null;
		}
		return f.get();
	}

	private static Shell activeShellOrNull() {
		try {
			return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
		} catch (Exception e) {
			Display display = UIThread.display();
			return display != null ? display.getActiveShell() : null;
		}
	}

	/**
	 * Turns a room-creation/join failure into a message a user can act on.
	 * The OCT server reports transport failures (unreachable host, DNS
	 * failure, connection refused) as a generic Node.js {@code TypeError:
	 * fetch failed} nested inside a {@link
	 * ResponseErrorException} message, so we detect
	 * those patterns and name the server URL instead of surfacing the raw
	 * exception text.
	 */
	public static String describeConnectionError(Throwable e, String serverUrl) {
		if (e instanceof TimeoutException) {
			return "Server " + serverUrl + " did not respond in time.";
		}
		if (e instanceof CancellationException) {
			return "The connection attempt was cancelled.";
		}
		// Depth cap rather than a self-reference check: a cause chain that cycles
		// through more than one throwable (A -> B -> A) isn't caught by comparing
		// only a cause to itself, and would otherwise spin forever.
		Throwable cause = e;
		for (int depth = 0; cause != null && depth < 8; depth++) {
			String msg = cause.getMessage();
			if (msg != null && (msg.contains("fetch failed") || msg.contains("ECONNREFUSED")
					|| msg.contains("ENOTFOUND") || msg.contains("EAI_AGAIN"))) {
				return "Server " + serverUrl + " is not reachable.";
			}
			cause = cause.getCause();
		}
		return e.getMessage() == null ? "" : ("Error: " + e.getMessage());
	}

	/**
	 * Logs a failed connect attempt and puts it in front of the user. Returns
	 * the message shown, so a job can carry the same wording in its status.
	 */
	private static String reportConnectFailure(String what, Exception e, String serverUrl) {
		LOG.log(Level.SEVERE, what, e);
		String message = what + " " + describeConnectionError(e, serverUrl);
		UIThread.asyncExec(() -> MessageDialog.openError(activeShellOrNull(), "OCT Error", message));
		return message;
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
