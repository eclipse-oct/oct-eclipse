/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.prefs.OCTSettings;
import org.eclipse.oct.protocol.Workspace;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for the connect-cancellation exec plan: cancelling the
 * "Creating OCT room..." progress job must end promptly with
 * {@link Status#CANCEL_STATUS}, and must not leave a {@code ServiceProcess} or
 * {@code CollaborationInstance} registered for the project.
 *
 * <p>
 * Uses the real {@link SessionService} singleton wired by {@code Activator}
 * and a real (but unreachable) server URL, so a genuine
 * {@code oct-service-process} is spawned and must genuinely be torn down —
 * not a mock standing in for one.
 */
class ConnectCancellationTest {

	// TEST-NET-1 (RFC 5737): reserved, never routed on any real network — a
	// connection attempt here blocks on the TCP handshake instead of failing
	// fast the way a DNS lookup error (ENOTFOUND) would, so the createRoom
	// future is still pending by the time the test cancels the job.
	private static final String UNREACHABLE_SERVER_URL = "http://192.0.2.1:9/";

	private String previousServerUrl;
	private IProject project;

	@BeforeEach
	void setUp() throws Exception {
		previousServerUrl = OCTSettings.getInstance().getDefaultServerURL();
		OCTSettings.getInstance().setDefaultServerURL(UNREACHABLE_SERVER_URL);
		project = EclipseTestProjects.createProject("cancel-create");
	}

	@AfterEach
	void tearDown() {
		OCTSettings.getInstance().setDefaultServerURL(previousServerUrl);
		EclipseTestProjects.deleteProject(project);
	}

	@Test
	@DisplayName("cancelling the connect job ends promptly with CANCEL_STATUS and leaves no process/instance behind")
	void cancellingCreateRoomEndsPromptlyWithNoLeftoverState() throws Exception {
		SessionService service = SessionService.getInstance();
		assertNotNull(service, "Activator must have wired SessionService.getInstance()");

		Workspace ws = new Workspace(project.getName(), new String[] { "testFolder" });
		service.createRoom(ws, project);

		Job job = findJob("Creating OCT room...");
		assertNotNull(job, "createRoom must schedule a \"Creating OCT room...\" job");

		CompletableFuture<IStatus> done = new CompletableFuture<>();
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				done.complete(event.getResult());
			}
		});

		awaitRunning(job);
		job.cancel();

		IStatus result = done.get(10, TimeUnit.SECONDS);
		assertEquals(IStatus.CANCEL, result.getSeverity(), "cancelling must yield CANCEL_STATUS, not an error");
		assertFalse(service.hasOpenSession(project), "no CollaborationInstance may remain after a cancel");
		assertFalse(service.hasProcess(project), "no ServiceProcess may remain registered after a cancel");
	}

	private static Job findJob(String name) {
		for (Job job : Job.getJobManager().find(null)) {
			if (name.equals(job.getName())) {
				return job;
			}
		}
		return null;
	}

	/** Cancelling before run() ever starts would skip the job's teardown entirely. */
	private static void awaitRunning(Job job) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (job.getState() != Job.RUNNING) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Job never reached RUNNING state, was: " + job.getState());
			}
			Thread.sleep(10);
		}
	}
}
