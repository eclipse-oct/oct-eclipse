/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.fs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.eclipse.oct.prefs.OCTSettings;

/**
 * Decides whether a resource is excluded from sharing and change
 * observation, based on glob patterns configured in {@link OCTSettings}.
 * Matching is per resource name at every path segment, not against a full
 * relative path - so {@code target} or {@code node_modules} match that name
 * at any depth, matching {@code .gitignore}'s non-slash pattern semantics.
 */
public final class IgnoreMatcher {

	private static final Logger LOG = Logger.getLogger(IgnoreMatcher.class.getName());

	private static volatile List<PathMatcher> matchers = compile(OCTSettings.getInstance().getIgnorePatterns());

	static {
		OCTSettings.getInstance().addIgnorePatternsChangeListener(patterns -> matchers = compile(patterns));
	}

	private IgnoreMatcher() {
	}

	public static boolean isIgnored(String simpleName) {
		return matches(matchers, simpleName);
	}

	public static List<PathMatcher> compile(List<String> patterns) {
		List<PathMatcher> compiled = new ArrayList<>();
		for (String pattern : patterns) {
			if (pattern.isBlank()) {
				continue;
			}
			try {
				compiled.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
			} catch (IllegalArgumentException e) {
				LOG.warning("Ignoring malformed glob pattern '" + pattern + "': " + e.getMessage());
			}
		}
		return compiled;
	}

	public static boolean matches(List<PathMatcher> matchers, String simpleName) {
		Path name = Path.of(simpleName);
		for (PathMatcher matcher : matchers) {
			if (matcher.matches(name)) {
				return true;
			}
		}
		return false;
	}
}
