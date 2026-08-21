/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.protocol;

import java.util.Map;

/**
 * Initialization data received when joining or creating a session.
 */
public class InitData {
	public String protocol;
	public Peer host;
	public Peer[] guests;
	public Map<String, Boolean> permissions;
	public Map<String, Object> capabilities;
	public Workspace workspace;

	public InitData() {
	}
}
