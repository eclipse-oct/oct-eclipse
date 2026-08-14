/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.eclipse.oct.internal.util.OctPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit tests for {@link OctPaths}. Covers every path shape the plugin
 * actually sees: host {@code IFile.getFullPath()}, guest temp-project
 * relative, VS Code awareness, VS Code {@code fileSystem/*}, and
 * {@code oct://} URIs.
 */
class OctPathsTest {

	@Nested
	@DisplayName("normalize")
	class Normalize {

		@ParameterizedTest(name = "[{0}] → [{1}]")
		@CsvSource({
				"test-oct/foo.txt, test-oct/foo.txt",
				"/test-oct/foo.txt, test-oct/foo.txt",
				"//test-oct/foo.txt, test-oct/foo.txt",
				"test-oct/foo.txt/, test-oct/foo.txt",
				"/test-oct/foo.txt/, test-oct/foo.txt",
				"test-oct//foo.txt, test-oct/foo.txt",
				"test-oct\\foo.txt, test-oct/foo.txt",
				"/test-oct\\src\\A.java, test-oct/src/A.java",
				"test-oct, test-oct",
				"/test-oct, test-oct",
				"/, ''",
				"'', ''",
		})
		void canonicalizesSeparatorsAndSlashes(String input, String expected) {
			assertEquals(expected, OctPaths.normalize(input));
		}

		@ParameterizedTest
		@NullAndEmptySource
		void nullAndEmptyBecomeEmpty(String input) {
			assertEquals("", OctPaths.normalize(input));
		}
	}

	@Nested
	@DisplayName("toHostProjectRelative — fileSystem/* on the host")
	class ToHostProjectRelative {

		private static final String PROJECT = "test-oct";

		@ParameterizedTest(name = "[{0}] → [{1}]")
		@CsvSource({
				// Protocol / VS Code awareness (shared root = project name)
				"test-oct/foo.txt, foo.txt",
				"/test-oct/foo.txt, foo.txt",
				"test-oct/src/A.java, src/A.java",
				"test-oct/sub/deeper/deep.txt, sub/deeper/deep.txt",
				// Shared root itself
				"test-oct, ''",
				"/test-oct, ''",
				"test-oct/, ''",
				// VS Code fileSystem/* already stripped the shared-root segment
				"foo.txt, foo.txt",
				"src/A.java, src/A.java",
				"sub/deeper/deep.txt, sub/deeper/deep.txt",
				// Empty / root
				"'', ''",
				"/, ''",
				// Different first segment must stay intact (not our project)
				"other-proj/foo.txt, other-proj/foo.txt",
		})
		void stripsSharedRootOnlyWhenItMatchesTheProject(String input, String expected) {
			assertEquals(expected, OctPaths.toHostProjectRelative(input, PROJECT));
		}

		@Test
		@DisplayName("project name that is a prefix of another folder is not stripped")
		void doesNotStripPrefixOfAnotherName() {
			assertEquals("test-oct-extra/foo.txt",
					OctPaths.toHostProjectRelative("test-oct-extra/foo.txt", "test-oct"));
		}

		@Test
		@DisplayName("null project name leaves the normalized path as-is")
		void nullProjectName() {
			assertEquals("test-oct/foo.txt", OctPaths.toHostProjectRelative("/test-oct/foo.txt", null));
		}
	}

	@Nested
	@DisplayName("oct:// URI ↔ protocol path")
	class OctUri {

		@Test
		void fromOctUriStripsLeadingSlash() throws Exception {
			URI uri = new URI("oct", "o6VTqaPT9d6m7Pso2B7CkYBL", "/test-oct/foo.txt", null, null);
			assertEquals("test-oct/foo.txt", OctPaths.fromOctUri(uri));
		}

		@Test
		void fromOctUriSharedRootOnly() throws Exception {
			URI uri = new URI("oct", "roomId", "/test-oct", null, null);
			assertEquals("test-oct", OctPaths.fromOctUri(uri));
		}

		@Test
		void fromOctUriSessionRoot() throws Exception {
			URI uri = new URI("oct", "roomId", "/", null, null);
			assertEquals("", OctPaths.fromOctUri(uri));
		}

		@Test
		void toOctUriRoundTrips() throws Exception {
			URI uri = OctPaths.toOctUri("roomId", "test-oct/foo.txt");
			assertEquals("oct", uri.getScheme());
			assertEquals("roomId", uri.getAuthority());
			assertEquals("/test-oct/foo.txt", uri.getPath());
			assertEquals("test-oct/foo.txt", OctPaths.fromOctUri(uri));
		}

		@Test
		@DisplayName("toOctUri must not produce a double slash when the input already has one")
		void toOctUriDoesNotDoubleSlash() throws Exception {
			URI uri = OctPaths.toOctUri("roomId", "/test-oct/foo.txt");
			assertEquals("/test-oct/foo.txt", uri.getPath());
			assertFalse(uri.getPath().startsWith("//"), "leading slash on input must be normalized first");
		}

		@Test
		void fromOctUriNull() {
			assertEquals("", OctPaths.fromOctUri(null));
		}
	}

	@Nested
	@DisplayName("referToSameDocument — editor lookup across roles")
	class SameDocument {

		@ParameterizedTest(name = "{0} ≡ {1}")
		@CsvSource({
				// Host getFullPath vs VS Code / guest protocol
				"/test-oct/foo.txt, test-oct/foo.txt",
				"test-oct/foo.txt, test-oct/foo.txt",
				// Guest temp-project prefix vs protocol
				"test-oct-oct-1723001/test-oct/foo.txt, test-oct/foo.txt",
				"/test-oct-oct-1723001/test-oct/src/A.java, test-oct/src/A.java",
				// VS Code fileSystem/* (no shared-root) vs protocol
				"foo.txt, test-oct/foo.txt",
				"src/A.java, test-oct/src/A.java",
				// Windows separators
				"test-oct\\foo.txt, /test-oct/foo.txt",
		})
		void matchesAcrossKnownShapes(String a, String b) {
			assertTrue(OctPaths.referToSameDocument(a, b), a + " should match " + b);
			assertTrue(OctPaths.referToSameDocument(b, a), "match must be symmetric");
		}

		@ParameterizedTest(name = "{0} ≢ {1}")
		@CsvSource({
				"test-oct/foo.txt, test-oct/bar.txt",
				"test-oct/foo.txt, other/foo.txt",
				"foo.txt, foo.txt.bak",
				"xt, test.txt",
				"test-oct/foo, test-oct/foo.txt",
				"'', test-oct/foo.txt",
				"test-oct/foo.txt, ''",
		})
		void doesNotMatchDifferentFiles(String a, String b) {
			assertFalse(OctPaths.referToSameDocument(a, b), a + " must not match " + b);
		}

		@ParameterizedTest
		@ValueSource(strings = { "test-oct/foo.txt", "/test-oct/foo.txt", "test-oct\\foo.txt" })
		void equalsSelfAfterNormalize(String path) {
			assertTrue(OctPaths.referToSameDocument(path, path));
		}
	}

	@Nested
	@DisplayName("end-to-end shapes the plugin actually produces")
	class Scenarios {

		@Test
		@DisplayName("Eclipse host opens a file → wire path matches VS Code awareness")
		void hostEditorPathMatchesVscodeAwareness() {
			// IFile.getFullPath().toString() on the host
			String eclipseFullPath = "/test-oct/src/A.java";
			String vscodeAwareness = "test-oct/src/A.java";
			assertEquals(vscodeAwareness, OctPaths.normalize(eclipseFullPath));
			assertTrue(OctPaths.referToSameDocument(eclipseFullPath, vscodeAwareness));
		}

		@Test
		@DisplayName("Eclipse guest IFile in temp project → same wire path")
		void guestTempProjectRelativeIsProtocolPath() {
			// getProjectRelativePath() inside test-oct-oct-<ts>
			String guestProjectRelative = "test-oct/src/A.java";
			String hostFullPath = "/test-oct/src/A.java";
			assertEquals(OctPaths.normalize(hostFullPath), OctPaths.normalize(guestProjectRelative));
		}

		@Test
		@DisplayName("VS Code fileSystem/readFile path still resolves on the host")
		void vscodeFsPathResolvesOnHost() {
			assertEquals("src/A.java", OctPaths.toHostProjectRelative("src/A.java", "test-oct"));
			assertEquals("src/A.java", OctPaths.toHostProjectRelative("test-oct/src/A.java", "test-oct"));
			assertEquals("src/A.java", OctPaths.toHostProjectRelative("/test-oct/src/A.java", "test-oct"));
		}

		@Test
		@DisplayName("guest fileSystem/change URI invalidate uses the protocol path")
		void changeNotificationUri() throws Exception {
			URI uri = OctPaths.toOctUri("o6VTqaPT9d6m7Pso2B7CkYBL", "test-oct/foo.txt");
			assertEquals("test-oct/foo.txt", OctPaths.fromOctUri(uri));
			// Host change listener used to concatenate "/" + path; a path that
			// already had a slash would have produced oct://id//test-oct/...
			URI fromSlashed = OctPaths.toOctUri("o6VTqaPT9d6m7Pso2B7CkYBL", "/test-oct/foo.txt");
			assertEquals(uri.getPath(), fromSlashed.getPath());
		}
	}
}
