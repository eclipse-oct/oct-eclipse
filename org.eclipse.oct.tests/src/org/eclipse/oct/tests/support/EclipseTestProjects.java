/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

/**
 * Small helper that creates and tears down real {@link IProject}s inside the
 * Eclipse test workspace. Each call to {@link #createProject(String)} produces
 * a fresh project with a monotonically increasing suffix so tests never clash,
 * even when they run in the same JVM.
 */
public final class EclipseTestProjects {

	private static final AtomicInteger COUNTER = new AtomicInteger();

	private EclipseTestProjects() {
	}

	public static IProject createProject(String baseName) throws CoreException {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		String name = baseName + "-oct-test-" + System.currentTimeMillis() + "-" + COUNTER.incrementAndGet();
		IProject project = ws.getRoot().getProject(name);
		project.create(new NullProgressMonitor());
		project.open(new NullProgressMonitor());
		return project;
	}

	public static IFolder createFolder(IProject project, String relative) throws CoreException {
		IPath path = new Path(relative);
		IFolder folder = project.getFolder(path.segment(0));
		if (!folder.exists()) {
			folder.create(true, true, new NullProgressMonitor());
		}
		for (int i = 1; i < path.segmentCount(); i++) {
			folder = folder.getFolder(path.segment(i));
			if (!folder.exists()) {
				folder.create(true, true, new NullProgressMonitor());
			}
		}
		return folder;
	}

	public static IFile writeFile(IProject project, String relative, String content) throws CoreException {
		return writeFile(project, relative, content.getBytes(StandardCharsets.UTF_8));
	}

	public static IFile writeFile(IProject project, String relative, byte[] content) throws CoreException {
		IPath path = new Path(relative);
		if (path.segmentCount() > 1) {
			createFolder(project, path.removeLastSegments(1).toString());
		}
		IFile file = project.getFile(path);
		ByteArrayInputStream stream = new ByteArrayInputStream(content);
		if (file.exists()) {
			file.setContents(stream, true, false, new NullProgressMonitor());
		} else {
			file.create(stream, true, new NullProgressMonitor());
		}
		return file;
	}

	public static void deleteProject(IProject project) {
		if (project == null || !project.exists()) {
			return;
		}
		try {
			project.delete(true, true, new NullProgressMonitor());
		} catch (CoreException e) {
			// Best effort during teardown.
		}
	}
}
