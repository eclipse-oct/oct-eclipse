/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.oct.internal.util.OctPaths;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OctPaths} helpers that need a real Eclipse {@code IFile}, so the
 * {@code getFullPath()} / {@code getProjectRelativePath()} shapes are locked
 * against the workspace, not just string fixtures.
 */
class OctPathsResourceTest {

	private IProject hostProject;
	private IProject guestProject;

	@AfterEach
	void cleanup() {
		EclipseTestProjects.deleteProject(hostProject);
		EclipseTestProjects.deleteProject(guestProject);
	}

	@Test
	@DisplayName("host IFile.getFullPath() becomes the protocol path (no leading slash)")
	void hostFullPathIsProtocolPath() throws Exception {
		hostProject = EclipseTestProjects.createProject("test-oct");
		IFile file = EclipseTestProjects.writeFile(hostProject, "src/A.java", "class A {}");

		assertTrue(file.getFullPath().toString().startsWith("/"),
				"Eclipse getFullPath() includes a leading slash");
		assertEquals("test-oct/src/A.java", OctPaths.fromHostResource(file));
		assertEquals("test-oct/src/A.java", OctPaths.fromEditorFile(file, true));
		assertEquals("src/A.java",
				OctPaths.toHostProjectRelative(OctPaths.fromHostResource(file), hostProject.getName()));
	}

	@Test
	@DisplayName("guest temp-project IFile project-relative path equals the host protocol path")
	void guestTempProjectRelativeMatchesHostProtocol() throws Exception {
		hostProject = EclipseTestProjects.createProject("test-oct");
		IFile hostFile = EclipseTestProjects.writeFile(hostProject, "src/A.java", "class A {}");

		// Mirrors SessionService.createTempProject + linked folder named after
		// the host shared root: <workspace>-oct-<ts>/<sharedRoot>/...
		guestProject = EclipseTestProjects.createProject("test-oct-oct-1723001");
		IFile guestFile = EclipseTestProjects.writeFile(guestProject, "test-oct/src/A.java", "class A {}");

		assertEquals("test-oct/src/A.java", OctPaths.fromGuestResource(guestFile));
		assertEquals("test-oct/src/A.java", OctPaths.fromEditorFile(guestFile, false));
		assertEquals(OctPaths.fromHostResource(hostFile), OctPaths.fromGuestResource(guestFile));
		assertTrue(OctPaths.referToSameDocument(hostFile.getFullPath().toString(),
				guestFile.getProjectRelativePath().toString()));
	}
}
