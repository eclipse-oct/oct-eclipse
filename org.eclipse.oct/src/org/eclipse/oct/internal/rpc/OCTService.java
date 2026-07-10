/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.Workspace;

/**
 * Remote interface for the OCT service process.
 * Matches the protocol methods in messages.ts (ToServiceMessages).
 */
public interface OCTService extends BaseMessageHandler.BaseRemoteInterface {

    @JsonRequest
    CompletableFuture<String> login();

    @JsonRequest(value = "room/joinRoom")
    CompletableFuture<SessionData> joinRoom(String roomId);

    @JsonRequest(value = "room/createRoom")
    CompletableFuture<SessionData> createRoom(Workspace workspace);

    @JsonRequest(value = "room/closeSession")
    CompletableFuture<Void> closeSession();

    @JsonNotification(value = "awareness/openDocument")
    void openDocument(String type, String documentUri, String text);

    @JsonRequest(value = "awareness/getDocumentContent")
    CompletableFuture<FileContent> getDocumentContent(String path);

    @JsonNotification(value = "awareness/updateTextSelection")
    void updateTextSelection(String path, ClientTextSelection[] textSelections);

    @JsonNotification(value = "awareness/updateDocument")
    void updateDocument(String path, org.eclipse.oct.internal.protocol.TextDocumentInsert[] updates);
}
