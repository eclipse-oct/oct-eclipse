/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.FileSystemStat;
import org.eclipse.oct.internal.protocol.FileType;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.oct.internal.rpc.FileSystemService;
import org.eclipse.oct.tests.support.EclipseTestProjects;
import org.eclipse.oct.tests.support.OctTestServer;
import org.eclipse.oct.tests.support.TestPeer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * Exercises host-side path conversion in
 * {@code WorkspaceFileSystemService.toRelativePath} via the real
 * {@code fileSystem/*} RPCs issued by a guest {@link TestPeer}.
 *
 * <p>The Eclipse plugin uses the convention that a shared workspace advertises
 * the host project's name both as the workspace name and as its single shared
 * folder. Protocol paths therefore always take the shape
 * {@code <projectName>/<relative/path>}.
 */
@TestInstance(Lifecycle.PER_CLASS)
class PathConversionTest {

	private String serverUrl;

	private TestPeer host;
	private TestPeer guest;
	private IProject hostProject;
	private String projectName;
	private String hostId;
	private FileSystemService fs;

	@BeforeAll
	void startServer() {
		serverUrl = OctTestServer.ensureStarted().serverUrl();
	}

	@BeforeEach
	void establishSession() throws Exception {
		hostProject = EclipseTestProjects.createProject("path-host");
		projectName = hostProject.getName();

		// Populate the host workspace with real files/folders on disk so guest
		// RPCs come back with actual content, not empty results.
		EclipseTestProjects.writeFile(hostProject, "test.txt", "HELLO WORLD!");
		EclipseTestProjects.writeFile(hostProject, "sub/nested.txt", "nested content");
		EclipseTestProjects.writeFile(hostProject, "sub/deeper/deep.txt", "deep!");

		host = new TestPeer(serverUrl, "host", TestPeer.Role.HOST);
		guest = new TestPeer(serverUrl, "guest", TestPeer.Role.GUEST);

		Workspace ws = new Workspace(projectName, new String[] { projectName });
		SessionData hostSession = host.createRoom(hostProject, ws);
		guest.joinRoom(hostSession.roomId);
		hostId = guest.awaitInit(30, TimeUnit.SECONDS).host.id;
		fs = guest.fileSystemService();
	}

	@AfterEach
	void closeSession() {
		if (guest != null) {
			guest.close();
		}
		if (host != null) {
			host.close();
		}
		EclipseTestProjects.deleteProject(hostProject);
	}

	@AfterAll
	void done() {
		// Server closes via shutdown hook.
	}


	@Test
	@DisplayName("stat of the shared root folder resolves to the project itself")
	void statOfSharedRoot() throws Exception {
		FileSystemStat root = fs.stat(projectName, hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(root, "stat of root must return metadata");
		assertEquals(FileType.Directory, root.type, "root of the shared folder must be a Directory");

		// Same request with a leading slash must also resolve to the project.
		FileSystemStat rootLeadingSlash = fs.stat("/" + projectName, hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(rootLeadingSlash, "stat with leading slash must still resolve");
		assertEquals(FileType.Directory, rootLeadingSlash.type);
	}

	@Test
	@DisplayName("readFile round-trips content for '<projectName>/<file>' paths")
	void readFileAtRoot() throws Exception {
		FileContent content = fs.readFile(projectName + "/test.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(content, "readFile must return content for an existing file");
		assertNotNull(content.content);
		assertEquals("HELLO WORLD!", new String(content.content, StandardCharsets.UTF_8));

		// Leading slash variant must produce the same content.
		FileContent leading = fs.readFile("/" + projectName + "/test.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(leading);
		assertArrayEquals(content.content, leading.content, "leading-slash path must resolve identically");
	}

	@Test
	@DisplayName("readFile works for nested subfolder paths")
	void readFileNested() throws Exception {
		FileContent nested = fs.readFile(projectName + "/sub/nested.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(nested);
		assertEquals("nested content", new String(nested.content, StandardCharsets.UTF_8));

		FileContent deep = fs.readFile(projectName + "/sub/deeper/deep.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(deep);
		assertEquals("deep!", new String(deep.content, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("stat reports files as File and directories as Directory")
	void statTypesReflectResourceKind() throws Exception {
		FileSystemStat file = fs.stat(projectName + "/test.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(file);
		assertEquals(FileType.File, file.type);
		assertTrue(file.size > 0, "size of a non-empty file must be reported");

		FileSystemStat folder = fs.stat(projectName + "/sub", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(folder);
		assertEquals(FileType.Directory, folder.type);
	}

	@Test
	@DisplayName("stat of a missing path returns null")
	void statOfMissingPath() throws Exception {
		FileSystemStat missing = fs.stat(projectName + "/does-not-exist.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNull(missing, "stat of a non-existent path should resolve to null");
	}

	@Test
	@DisplayName("readDir returns child names with correct FileType wire values")
	void readDirEntries() throws Exception {
		Map<String, FileType> entries = fs.readDir(projectName, hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(entries, "readDir must not return null for the shared root");
		assertEquals(FileType.File, entries.get("test.txt"), "test.txt should be a File");
		assertEquals(FileType.Directory, entries.get("sub"), "sub should be a Directory");

		Map<String, FileType> nested = fs.readDir(projectName + "/sub", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(nested);
		assertEquals(FileType.File, nested.get("nested.txt"));
		assertEquals(FileType.Directory, nested.get("deeper"));
	}

	@Test
	@DisplayName("writeFile creates a new file on disk in the host project")
	void writeFileCreatesFileOnDisk() throws Exception {
		String path = projectName + "/created.txt";
		byte[] payload = "written by guest".getBytes(StandardCharsets.UTF_8);
		fs.writeFile(path, new FileContent(payload), hostId).get(15, TimeUnit.SECONDS);

		hostProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		IFile created = hostProject.getFile("created.txt");
		assertTrue(created.exists(), "writeFile must produce a real file on disk");
		byte[] onDisk;
		try (var is = created.getContents()) {
			onDisk = is.readAllBytes();
		}
		assertArrayEquals(payload, onDisk, "written bytes must match on disk");

		// And read the same path back via RPC to close the loop.
		FileContent readBack = fs.readFile(path, hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(readBack);
		assertArrayEquals(payload, readBack.content);
	}

	@Test
	@DisplayName("writeFile creates parent folders when they don't exist yet")
	void writeFileCreatesParents() throws Exception {
		String path = projectName + "/generated/new/child.txt";
		byte[] payload = "created via nested write".getBytes(StandardCharsets.UTF_8);
		fs.writeFile(path, new FileContent(payload), hostId).get(15, TimeUnit.SECONDS);

		hostProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		IFile created = hostProject.getFile(new Path("generated/new/child.txt"));
		assertTrue(created.exists(), "parent folders must have been auto-created");
	}

	@Test
	@DisplayName("mkdir creates a folder resolvable via readDir")
	void mkdirRoundTrip() throws Exception {
		fs.mkdir(projectName + "/newdir", hostId).get(15, TimeUnit.SECONDS);
		hostProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		assertTrue(hostProject.getFolder("newdir").exists(), "mkdir must create a real folder");
		Map<String, FileType> entries = fs.readDir(projectName, hostId).get(15, TimeUnit.SECONDS);
		assertEquals(FileType.Directory, entries.get("newdir"));
	}

	@Test
	@DisplayName("delete removes files by their protocol path")
	void deleteFile() throws Exception {
		fs.delete(projectName + "/test.txt", hostId).get(15, TimeUnit.SECONDS);
		hostProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		assertFalse(hostProject.getFile("test.txt").exists(), "delete must remove the file on disk");
		FileSystemStat missing = fs.stat(projectName + "/test.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNull(missing, "stat of the deleted file must return null");
	}

	@Test
	@DisplayName("rename moves a file across folders")
	void renameFile() throws Exception {
		fs.rename(projectName + "/test.txt", projectName + "/sub/renamed.txt", hostId).get(15, TimeUnit.SECONDS);
		hostProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
		assertFalse(hostProject.getFile("test.txt").exists(), "source must be gone after rename");
		assertTrue(hostProject.getFile(new Path("sub/renamed.txt")).exists(), "target must exist after rename");
		FileContent renamed = fs.readFile(projectName + "/sub/renamed.txt", hostId).get(15, TimeUnit.SECONDS);
		assertNotNull(renamed);
		assertEquals("HELLO WORLD!", new String(renamed.content, StandardCharsets.UTF_8));
	}
}
