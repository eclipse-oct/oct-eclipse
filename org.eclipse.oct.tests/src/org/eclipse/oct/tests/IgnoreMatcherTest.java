/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.PathMatcher;
import java.util.List;

import org.eclipse.oct.internal.fs.IgnoreMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure unit tests for {@link IgnoreMatcher}. No Eclipse/preferences
 * dependency - {@code compile}/{@code matches} are exercised directly
 * against explicit pattern lists.
 */
class IgnoreMatcherTest {

	@Nested
	@DisplayName("matches — well-known default patterns")
	class DefaultPatterns {

		private final List<PathMatcher> matchers = IgnoreMatcher
				.compile(List.of("bin", "target", ".DS_Store", "node_modules", ".git"));

		@ParameterizedTest(name = "[{0}] is ignored")
		@CsvSource({ "bin", "target", ".DS_Store", "node_modules", ".git" })
		void matchesExactName(String name) {
			assertTrue(IgnoreMatcher.matches(matchers, name));
		}

		@ParameterizedTest(name = "[{0}] is not ignored")
		@CsvSource({ "binary", "targetx", "my-node_modules", "src", "A.java" })
		void doesNotMatchUnrelatedNames(String name) {
			assertFalse(IgnoreMatcher.matches(matchers, name));
		}
	}

	@Nested
	@DisplayName("matches — glob wildcards")
	class Wildcards {

		@Test
		void starMatchesAnySuffix() {
			List<PathMatcher> matchers = IgnoreMatcher.compile(List.of("*.class"));
			assertTrue(IgnoreMatcher.matches(matchers, "Foo.class"));
			assertFalse(IgnoreMatcher.matches(matchers, "Foo.java"));
		}

		@Test
		void emptyPatternListMatchesNothing() {
			assertFalse(IgnoreMatcher.matches(List.of(), "target"));
		}
	}

	@Nested
	@DisplayName("compile — resilience")
	class Compile {

		@Test
		@DisplayName("malformed pattern is skipped, not thrown")
		void skipsMalformedPattern() {
			List<PathMatcher> matchers = IgnoreMatcher.compile(List.of("target", "["));
			assertEquals(1, matchers.size());
			assertTrue(IgnoreMatcher.matches(matchers, "target"));
		}

		@Test
		@DisplayName("blank entries are skipped")
		void skipsBlankEntries() {
			List<PathMatcher> matchers = IgnoreMatcher.compile(List.of("target", "", "  "));
			assertEquals(1, matchers.size());
		}
	}
}
