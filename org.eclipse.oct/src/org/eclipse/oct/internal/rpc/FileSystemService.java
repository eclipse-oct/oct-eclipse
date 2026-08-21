/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;
import org.eclipse.oct.protocol.FileChangeEvent;
import org.eclipse.oct.protocol.FileContent;
import org.eclipse.oct.protocol.FileSystemStat;
import org.eclipse.oct.protocol.FileType;

/**
 * Remote interface for the file system service. Matches fileSystem/* methods in
 * messages.ts.
 */
@JsonSegment("fileSystem")
public interface FileSystemService extends BaseMessageHandler.BaseRemoteInterface {

	@JsonRequest
	CompletableFuture<FileSystemStat> stat(String path, String target);

	@JsonRequest
	CompletableFuture<FileContent> readFile(String path, String target);

	@JsonRequest
	CompletableFuture<Map<String, FileType>> readDir(String path, String target);

	@JsonRequest
	CompletableFuture<Void> mkdir(String path, String target);

	@JsonRequest
	CompletableFuture<Void> writeFile(String path, FileContent content, String target);

	@JsonRequest
	CompletableFuture<Void> delete(String path, String target);

	@JsonRequest
	CompletableFuture<Void> rename(String oldPath, String newPath, String target);

	@JsonNotification
	void change(FileChangeEvent event, String broadcast);
}
