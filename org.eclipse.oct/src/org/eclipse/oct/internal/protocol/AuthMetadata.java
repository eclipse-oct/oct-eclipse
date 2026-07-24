/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * Authentication metadata including providers and login page URL.
 */
public class AuthMetadata {
	public AuthProvider[] providers;
	public String loginPageUrl;
	public String defaultSuccessUrl;

	public AuthMetadata() {
	}
}
