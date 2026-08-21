/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.protocol;

public class FileSystemStat {
	public FileType type;
	public long mtime;
	public long ctime;
	public long size;
	public Long permissions;

	public FileSystemStat() {
	}

	public FileSystemStat(FileType type, long mtime, long ctime, long size, Long permissions) {
		this.type = type;
		this.mtime = mtime;
		this.ctime = ctime;
		this.size = size;
		this.permissions = permissions;
	}
}
