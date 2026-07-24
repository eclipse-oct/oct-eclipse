/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * File change kinds matching {@code FileChangeEventType} in the OCT protocol
 * (numeric wire values: Create=0, Update=1, Delete=2).
 */
public enum FileChangeEventType {
	Create(0), Update(1), Delete(2);

	private final int value;

	FileChangeEventType(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static FileChangeEventType fromValue(int value) {
		for (FileChangeEventType t : values()) {
			if (t.value == value) {
				return t;
			}
		}
		return Update;
	}
}
