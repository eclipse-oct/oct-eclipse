/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.util;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

/**
 * Single place for OCT protocol path conversion.
 *
 * <p>The wire/Yjs key is always {@code sharedRoot/relative/path} — no leading
 * slash, no Eclipse temp-project prefix. That is what VS Code's
 * {@code CollaborationUri.getProtocolPath} emits and what
 * {@code awareness/openDocument} / {@code awareness/updateDocument} carry.
 *
 * <pre>
 * Host Eclipse IFile          /test-oct/src/A.java
 *   → protocol                test-oct/src/A.java
 *
 * Eclipse guest temp project  /test-oct-oct-1723/test-oct/src/A.java
 *   → protocol (proj-rel)     test-oct/src/A.java
 *
 * VS Code guest URI           oct:///test-oct/src/A.java
 *   → protocol                test-oct/src/A.java
 *
 * VS Code fileSystem/*        src/A.java   (shared-root prefix already stripped)
 *   → host project-relative   src/A.java
 *
 * oct://&lt;roomId&gt;/test-oct/src/A.java
 *   → protocol                test-oct/src/A.java
 * </pre>
 */
public final class OctPaths {

	private OctPaths() {
	}

	/**
	 * Canonical protocol path: {@code /} and {@code \} become {@code /}, leading
	 * and trailing slashes are stripped, empty segments collapsed. {@code null}
	 * and blank become {@code ""}.
	 */
	public static String normalize(String path) {
		if (path == null || path.isEmpty()) {
			return "";
		}
		String n = path.replace('\\', '/');
		int start = 0;
		int end = n.length();
		while (start < end && n.charAt(start) == '/') {
			start++;
		}
		while (end > start && n.charAt(end - 1) == '/') {
			end--;
		}
		if (start == 0 && end == n.length() && n.indexOf("//") < 0) {
			return n;
		}
		StringBuilder sb = new StringBuilder(end - start);
		boolean prevSlash = false;
		for (int i = start; i < end; i++) {
			char c = n.charAt(i);
			if (c == '/') {
				if (!prevSlash) {
					sb.append(c);
				}
				prevSlash = true;
			} else {
				sb.append(c);
				prevSlash = false;
			}
		}
		return sb.toString();
	}

	/**
	 * Host resource → protocol path. {@link IResource#getFullPath()} is
	 * workspace-relative and includes a leading slash plus the project name,
	 * which <em>is</em> the shared-root name the plugin advertises.
	 */
	public static String fromHostResource(IResource resource) {
		if (resource == null) {
			return "";
		}
		return normalize(resource.getFullPath().toString());
	}

	/**
	 * Guest resource in the temp project → protocol path. The temp project is
	 * named {@code <workspace>-oct-<ts>}; the shared root is a linked folder
	 * inside it, so {@link IResource#getProjectRelativePath()} is already
	 * {@code sharedRoot/relative}.
	 */
	public static String fromGuestResource(IResource resource) {
		if (resource == null) {
			return "";
		}
		return normalize(resource.getProjectRelativePath().toString());
	}

	public static String fromEditorFile(IFile file, boolean isHost) {
		return isHost ? fromHostResource(file) : fromGuestResource(file);
	}

	/**
	 * Protocol (or VS Code FS) path → path relative to the hosted Eclipse
	 * project, for {@code IProject.findMember} / {@code getFile}.
	 *
	 * <ul>
	 * <li>{@code test-oct/src/A.java} + project {@code test-oct} → {@code src/A.java}</li>
	 * <li>{@code test-oct} + project {@code test-oct} → {@code ""} (the project itself)</li>
	 * <li>{@code src/A.java} (VS Code {@code fileSystem/*}) → {@code src/A.java}</li>
	 * <li>{@code /test-oct/src/A.java} → {@code src/A.java}</li>
	 * </ul>
	 */
	public static String toHostProjectRelative(String protocolPath, String projectName) {
		String n = normalize(protocolPath);
		if (n.isEmpty()) {
			return "";
		}
		if (projectName != null && !projectName.isEmpty()) {
			if (n.equals(projectName)) {
				return "";
			}
			if (n.startsWith(projectName + "/")) {
				return n.substring(projectName.length() + 1);
			}
		}
		return n;
	}

	public static IPath toHostProjectRelativePath(String protocolPath, String projectName) {
		return new Path(toHostProjectRelative(protocolPath, projectName));
	}

	/** {@code oct://roomId/sharedRoot/rel} → {@code sharedRoot/rel}. */
	public static String fromOctUri(URI uri) {
		if (uri == null) {
			return "";
		}
		return normalize(uri.getPath());
	}

	/**
	 * Protocol path → {@code oct://<sessionId>/<path>}. {@code path} is
	 * normalized first so a leading slash on the input cannot produce
	 * {@code oct://id//foo}.
	 */
	public static URI toOctUri(String sessionId, String protocolPath) throws URISyntaxException {
		return new URI("oct", sessionId, "/" + normalize(protocolPath), null, null);
	}

	/**
	 * Whether two paths name the same document after normalization, including
	 * the cases where one side still carries a leading slash, a guest
	 * temp-project prefix, or the VS Code FS form without the shared-root
	 * segment.
	 *
	 * <p>Match is only accepted at a {@code /} boundary, so {@code xt} does
	 * not match {@code test.txt}.
	 */
	public static boolean referToSameDocument(String a, String b) {
		String na = normalize(a);
		String nb = normalize(b);
		if (na.equals(nb)) {
			return true;
		}
		if (na.isEmpty() || nb.isEmpty()) {
			return false;
		}
		return na.endsWith("/" + nb) || nb.endsWith("/" + na);
	}
}
