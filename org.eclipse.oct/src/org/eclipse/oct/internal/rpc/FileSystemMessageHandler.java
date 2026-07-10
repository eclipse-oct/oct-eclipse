/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.fs.WorkspaceFileSystemService;
import org.eclipse.oct.internal.protocol.FileChangeEvent;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.FileSystemStat;
import org.eclipse.oct.internal.protocol.FileType;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Handles inbound file system RPC calls from the service process.
 * Port of FileSystemMessageHandler.kt.
 */
@JsonSegment("fileSystem")
public class FileSystemMessageHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getLogger(FileSystemMessageHandler.class.getName());

    public FileSystemMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated) {
        super(serverUrl, onSessionCreated);
    }

    @Override
    public Class<? extends BaseRemoteInterface> getRemoteInterface() {
        return FileSystemService.class;
    }

    private WorkspaceFileSystemService getWfs() {
        if (collaborationInstance == null) return null;
        return (WorkspaceFileSystemService) collaborationInstance.getWorkspaceFileSystem();
    }

    @JsonRequest
    public CompletableFuture<FileSystemStat> stat(String path, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> wfs.stat(path));
    }

    @JsonRequest
    public CompletableFuture<FileContent> readFile(String path, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> wfs.readFile(path));
    }

    @JsonRequest
    public CompletableFuture<Map<String, FileType>> readDir(String path, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(Map.of());
        return CompletableFuture.supplyAsync(() -> wfs.readDir(path));
    }

    @JsonRequest
    public CompletableFuture<Void> mkdir(String path, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return wfs.mkdir(path);
    }

    @JsonRequest
    public CompletableFuture<Void> writeFile(String path, FileContent content, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return wfs.writeFile(path, content);
    }

    @JsonRequest
    public CompletableFuture<Void> delete(String path, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return wfs.delete(path);
    }

    @JsonRequest
    public CompletableFuture<Void> rename(String oldPath, String newPath, String origin) {
        WorkspaceFileSystemService wfs = getWfs();
        if (wfs == null) return CompletableFuture.completedFuture(null);
        return wfs.rename(oldPath, newPath);
    }

    @JsonNotification
    public void change(FileChangeEvent event, String origin) {
        if (collaborationInstance != null) {
            collaborationInstance.handleFileSystemChange(event);
        }
    }
}
