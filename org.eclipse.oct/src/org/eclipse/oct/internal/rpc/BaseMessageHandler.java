/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Abstract base for message handlers. Port of BaseMessageHandler.kt.
 * Holds a reference to the CollaborationInstance, deferred callbacks,
 * and implements the LSP4J Endpoint interface.
 */
public abstract class BaseMessageHandler implements Endpoint {

    /**
     * Marker interface for remote service proxies.
     */
    public interface BaseRemoteInterface {}

    protected volatile CollaborationInstance collaborationInstance;
    protected final List<Runnable> executeOnSetInstance = new ArrayList<>();
    protected final String serverUrl;

    public abstract Class<? extends BaseRemoteInterface> getRemoteInterface();

    public BaseMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated) {
        this.serverUrl = serverUrl;
        onSessionCreated.onEvent(instance -> {
            this.collaborationInstance = instance;
            List<Runnable> pending = new ArrayList<>(executeOnSetInstance);
            executeOnSetInstance.clear();
            pending.forEach(Runnable::run);
        });
    }

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        throw new UnsupportedOperationException("Unhandled request: " + method);
    }

    @Override
    public void notify(String method, Object parameter) {
        throw new UnsupportedOperationException("Unhandled notification: " + method);
    }
}
