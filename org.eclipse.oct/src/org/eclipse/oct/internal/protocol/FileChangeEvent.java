/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class FileChangeEvent {
	public FileChange[] changes;

	public FileChangeEvent() {
	}

	public FileChangeEvent(FileChange[] changes) {
		this.changes = changes;
	}
}
