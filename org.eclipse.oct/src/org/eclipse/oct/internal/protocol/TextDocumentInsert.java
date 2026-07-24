/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class TextDocumentInsert {
	public int startOffset;
	public Integer endOffset;
	public String text;

	public TextDocumentInsert() {
	}

	public TextDocumentInsert(int startOffset, Integer endOffset, String text) {
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.text = text;
	}
}
