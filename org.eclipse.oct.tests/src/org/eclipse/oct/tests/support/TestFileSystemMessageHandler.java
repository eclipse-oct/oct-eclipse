/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.rpc.FileSystemMessageHandler;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Records inbound {@code fileSystem/writeFile} requests so tests can assert
 * that a host save was propagated to a guest (VS Code only {@code document.save()}s
 * on writeFile, not on {@code fileSystem/change}).
 */
public class TestFileSystemMessageHandler extends FileSystemMessageHandler {

	private final List<WriteFileEvent> writeFiles = new CopyOnWriteArrayList<>();
	private final CompletableFuture<WriteFileEvent> firstWriteFile = new CompletableFuture<>();

	public TestFileSystemMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated) {
		super(serverUrl, onSessionCreated);
	}

	@Override
	public CompletableFuture<Void> writeFile(String path, FileContent content, String origin) {
		WriteFileEvent event = new WriteFileEvent(path, content != null ? content.content : null, origin);
		writeFiles.add(event);
		firstWriteFile.complete(event);
		return super.writeFile(path, content, origin);
	}

	public CompletableFuture<WriteFileEvent> firstWriteFile() {
		return firstWriteFile;
	}

	public List<WriteFileEvent> writeFiles() {
		return writeFiles;
	}

	public record WriteFileEvent(String path, byte[] content, String origin) {
	}
}
