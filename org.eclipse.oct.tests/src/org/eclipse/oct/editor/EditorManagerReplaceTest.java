/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EditorManagerReplaceTest {

	@Test
	@DisplayName("skip a Yjs seed insert that matches the already-loaded editor")
	void skipSeedThatMatchesEditor() {
		Document doc = new Document("hello world");
		assertTrue(EditorManager.isSeedEcho(doc, 0, 0, "hello world"));
	}

	@Test
	@DisplayName("typing a character at offset 0 is never treated as a seed")
	void allowOneCharacterInsertAtStart() {
		Document doc = new Document("111111111");
		assertFalse(EditorManager.isSeedEcho(doc, 0, 0, "2"));
	}

	@Test
	@DisplayName("inserting the same single character at 0 must still apply (aa)")
	void allowDoublingAOneCharacterDocument() {
		Document doc = new Document("2");
		assertFalse(EditorManager.isSeedEcho(doc, 0, 0, "2"));
	}

	@Test
	@DisplayName("insert into an empty editor is not a seed echo")
	void allowInsertIntoEmpty() {
		Document doc = new Document("");
		assertFalse(EditorManager.isSeedEcho(doc, 0, 0, "hello"));
	}

	@Test
	@DisplayName("normal mid-document replace is not a seed echo")
	void leaveNormalReplaceAlone() {
		Document doc = new Document("hello");
		assertFalse(EditorManager.isSeedEcho(doc, 1, 2, "xx"));
	}
}
