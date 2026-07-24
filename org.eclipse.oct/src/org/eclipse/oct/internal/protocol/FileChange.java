/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class FileChange {
	public FileChangeEventType type;
	public String path;

	public FileChange() {
	}

	public FileChange(FileChangeEventType type, String path) {
		this.type = type;
		this.path = path;
	}
}
