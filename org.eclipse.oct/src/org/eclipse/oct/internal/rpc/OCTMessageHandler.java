/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.auth.AuthenticationService;
import org.eclipse.oct.internal.protocol.AuthMetadata;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.protocol.SessionData;
import org.eclipse.oct.internal.protocol.User;
import org.eclipse.oct.internal.protocol.Workspace;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.util.EventEmitter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Handles inbound OCT messages from the service process.
 * Port of OCTMessageHandler.kt.
 */
public class OCTMessageHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getLogger(OCTMessageHandler.class.getName());

    public OCTMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated) {
        super(serverUrl, onSessionCreated);
    }

    @Override
    public Class<? extends BaseRemoteInterface> getRemoteInterface() {
        return OCTService.class;
    }

    // ---- Inbound notifications from service process ----

    @JsonNotification
    public void authentication(String token, AuthMetadata metadata) {
        AuthenticationService.getInstance().authenticate(serverUrl, token, metadata);
    }

    @JsonNotification
    public void error(String message, String stack) {
        LOG.severe("OCT service error: " + message);
        if (stack != null) LOG.severe("Stack: " + stack);
    }

    @JsonRequest(value = "room/joinSessionRequest")
    public CompletableFuture<Boolean> joinSessionRequest(User user) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Display display = Display.getDefault();
        display.asyncExec(() -> {
            String displayName = (user.email != null && !user.email.isEmpty())
                ? user.name + " (" + user.email + ")"
                : user.name;
            boolean accepted = MessageDialog.openQuestion(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                "Join Request",
                displayName + " via " + user.authProvider + " wants to join the collaboration session. Accept?"
            );
            result.complete(accepted);
        });
        return result;
    }

    @JsonNotification
    public void init(InitData initData) {
        if (collaborationInstance != null) {
            collaborationInstance.initPeers(initData);
        } else {
            executeOnSetInstance.add(() -> collaborationInstance.initPeers(initData));
        }
    }

    @JsonNotification
    public void peerInfo(Peer peer) {
        if (collaborationInstance != null) {
            collaborationInstance.identity = peer;
        } else {
            executeOnSetInstance.add(() -> collaborationInstance.identity = peer);
        }
    }

    @JsonNotification
    public void peerJoined(Peer peer) {
        if (collaborationInstance != null) {
            collaborationInstance.peerJoined(peer);
        }
    }

    @JsonNotification
    public void peerLeft(Peer peer) {
        if (collaborationInstance != null) {
            collaborationInstance.peerLeft(peer);
        }
    }

    @JsonNotification(value = "awareness/updateTextSelection")
    public void updateTextSelection(String url, ClientTextSelection[] selections) {
        if (collaborationInstance != null) {
            collaborationInstance.updateTextSelection(url, selections);
        }
    }

    @JsonNotification(value = "awareness/updateDocument")
    public void updateDocument(String url, TextDocumentInsert[] updates) {
        if (collaborationInstance != null) {
            collaborationInstance.updateDocument(url, updates);
        }
    }

    @JsonNotification
    public void editorOpened(String documentPath, String peerId) {
        if (collaborationInstance != null) {
            collaborationInstance.editorOpened(documentPath, peerId);
        }
    }

    @JsonNotification
    public void sessionClosed() {
        if (collaborationInstance != null && !collaborationInstance.isHost) {
            Display.getDefault().asyncExec(() -> {
                IProjectCloser closer = () -> {
                    try {
                        collaborationInstance.project.close(null);
                    } catch (org.eclipse.core.runtime.CoreException e) {
                        LOG.warning("Failed to close guest project: " + e.getMessage());
                    }
                };
                closer.close();
            });
        }
    }

    @FunctionalInterface
    private interface IProjectCloser {
        void close();
    }
}
