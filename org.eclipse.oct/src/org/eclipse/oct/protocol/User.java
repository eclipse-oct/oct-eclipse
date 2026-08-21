/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.protocol;

/**
 * User info sent in join session requests.
 */
public class User {
	public String name;
	public String email;
	public String authProvider;

	public User() {
	}
}
