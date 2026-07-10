/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.fs;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileSystem;

/**
 * EFS guest file system for the "oct" scheme.
 * Port of OCTSessionFileSystem.kt.
 *
 * URI shape: oct://<sessionId>/<sharedRoot>/<relative/path>
 * The authority is the session room ID; path[0] is the shared root name.
 */
public class OctFileSystem extends FileSystem {

    /** Singleton instance set by the OSGi extension framework. */
    private static OctFileSystem instance;

    private final ConcurrentHashMap<String, OctFileStore> storeCache = new ConcurrentHashMap<>();

    public OctFileSystem() {
        instance = this;
    }

    public static OctFileSystem getInstance() {
        return instance;
    }

    @Override
    public IFileStore getStore(URI uri) {
        String key = uri.toString();
        return storeCache.computeIfAbsent(key, k -> new OctFileStore(uri));
    }

    @Override
    public int attributes() {
        // Writable, no exec tracking needed
        return 0;
    }

    @Override
    public boolean canDelete() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    /** Invalidate all cached stores for a given session (called on session close). */
    public void clearSession(String sessionId) {
        storeCache.keySet().removeIf(k -> k.startsWith("oct://" + sessionId + "/"));
    }

    /** Invalidate one store by path (called on fileSystem/change). */
    public void invalidate(URI uri) {
        OctFileStore store = storeCache.get(uri.toString());
        if (store != null) store.invalidateCache();
    }
}
