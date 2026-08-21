/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.protocol;

/**
 * File type enum matching the protocol values.
 */
public enum FileType {
	Unknown(0), File(1), Directory(2), SymbolicLink(64);

	private final int value;

	FileType(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static FileType fromValue(int value) {
		for (FileType ft : values()) {
			if (ft.value == value) {
				return ft;
			}
		}
		return Unknown;
	}
}
