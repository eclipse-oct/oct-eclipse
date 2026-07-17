/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.fs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileInfo;
import org.eclipse.core.filesystem.provider.FileStore;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.FileSystemStat;
import org.eclipse.oct.internal.protocol.FileType;
import org.eclipse.oct.internal.rpc.FileSystemService;
import org.eclipse.oct.internal.rpc.OCTService;

/**
 * EFS FileStore for the "oct://" guest file system.
 * Every operation is a synchronous JSON-RPC round trip to the host.
 */
public class OctFileStore extends FileStore {

    private static final Logger LOG = Logger.getLogger(OctFileStore.class.getName());
    private static final long RPC_TIMEOUT_SEC = 10;
    private static final String PLUGIN_ID = "org.eclipse.oct";

    private final URI uri;
    /** Cached IFileInfo — null means stale/not yet fetched. */
    private volatile IFileInfo cachedInfo;

    public OctFileStore(URI uri) {
        this.uri = normalize(uri);
    }

    // ---- Identity ----

    @Override
    public String getName() {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return uri.getAuthority(); // session root
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @Override
    public IFileStore getParent() {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return null; // root of oct FS
        }
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return null;
        try {
            URI parentUri = new URI(uri.getScheme(), uri.getAuthority(), path.substring(0, idx), null, null);
            return EFS.getStore(parentUri);
        } catch (URISyntaxException | CoreException e) {
            return null;
        }
    }

    @Override
    public URI toURI() {
        return uri;
    }

    // ---- Info / directory listing ----

    @Override
    public IFileInfo fetchInfo(int options, IProgressMonitor monitor) {
        if (cachedInfo != null) return cachedInfo;

        FileSystemStat stat = null;
        try {
            stat = rpc(getFileSystemService().stat(toOctPath(), hostPeerId()), monitor);
        } catch (CoreException e) {
            LOG.warning("stat failed for " + uri + ": " + e.getMessage());
        }

        FileInfo info = new FileInfo(getName());
        if (stat != null) {
            info.setExists(true);
            info.setDirectory(stat.type == FileType.Directory);
            info.setLength(stat.size);
            info.setLastModified(stat.mtime);
        } else {
            info.setExists(false);
        }
        cachedInfo = info;
        return info;
    }

    @Override
    public String[] childNames(int options, IProgressMonitor monitor) throws CoreException {
        Map<String, FileType> entries;
        try {
            entries = rpc(getFileSystemService().readDir(toOctPath(), hostPeerId()), monitor);
        } catch (CoreException e) {
            return new String[0];
        }
        if (entries == null) return new String[0];
        return entries.keySet().toArray(new String[0]);
    }

    @Override
    public IFileStore getChild(String name) {
        String newPath = uri.getPath().endsWith("/")
            ? uri.getPath() + name
            : uri.getPath() + "/" + name;
        try {
            URI childUri = new URI(uri.getScheme(), uri.getAuthority(), newPath, null, null);
            return EFS.getStore(childUri);
        } catch (URISyntaxException | CoreException e) {
            throw new RuntimeException("Invalid child URI", e);
        }
    }

    // ---- Read ----

    @Override
    public InputStream openInputStream(int options, IProgressMonitor monitor) throws CoreException {
        // Try awareness/getDocumentContent first (serves live editor state)
        OCTService octService = getOctService();
        if (octService != null) {
            try {
                FileContent content = rpc(octService.getDocumentContent(toOctPath()), monitor);
                if (content != null && content.content != null) {
                    return new ByteArrayInputStream(content.content);
                }
            } catch (CoreException e) {
                // Fall through to readFile
            }
        }

        // Fallback: fileSystem/readFile
        FileContent content = rpc(getFileSystemService().readFile(toOctPath(), hostPeerId()), monitor);
        if (content == null || content.content == null) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, "readFile returned null for: " + uri));
        }
        return new ByteArrayInputStream(content.content);
    }

    // ---- Write ----

    @Override
    public OutputStream openOutputStream(int options, IProgressMonitor monitor) throws CoreException {
        final boolean append = (options & EFS.APPEND) != 0;
        final byte[] existingBytes;

        if (append) {
            try {
                FileContent existing = rpc(getFileSystemService().readFile(toOctPath(), hostPeerId()), monitor);
                existingBytes = (existing != null && existing.content != null) ? existing.content : new byte[0];
            } catch (CoreException e) {
                throw e;
            }
        } else {
            existingBytes = null;
        }

        return new ByteArrayOutputStream() {
            {
                if (append && existingBytes != null) {
                    write(existingBytes, 0, existingBytes.length);
                }
            }

            @Override
            public void close() throws IOException {
                super.close();
                byte[] data = toByteArray();
                FileContent content = new FileContent(data);
                try {
                    rpc(getFileSystemService().writeFile(toOctPath(), content, hostPeerId()), null);
                    invalidateCache();
                } catch (CoreException e) {
                    throw new IOException("Failed to write file: " + uri, e);
                }
            }
        };
    }

    // ---- Mutating operations ----

    @Override
    public IFileStore mkdir(int options, IProgressMonitor monitor) throws CoreException {
        rpc(getFileSystemService().mkdir(toOctPath(), hostPeerId()), monitor);
        invalidateCache();
        return this;
    }

    @Override
    public void delete(int options, IProgressMonitor monitor) throws CoreException {
        rpc(getFileSystemService().delete(toOctPath(), hostPeerId()), monitor);
        invalidateCache();
        // Invalidate parent cache too
        IFileStore parent = getParent();
        if (parent instanceof OctFileStore ofs) ofs.invalidateCache();
    }

    @Override
    public void move(IFileStore destination, int options, IProgressMonitor monitor) throws CoreException {
        if (!(destination instanceof OctFileStore dest)) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "Cannot move oct:// file to non-oct destination"));
        }
        rpc(getFileSystemService().rename(toOctPath(), dest.toOctPath(), hostPeerId()), monitor);
        invalidateCache();
        dest.invalidateCache();
    }

    @Override
    public void putInfo(IFileInfo info, int options, IProgressMonitor monitor) throws CoreException {
        // Attribute changes not supported — silently ignore
    }

    // ---- Cache ----

    public void invalidateCache() {
        cachedInfo = null;
    }

    // ---- Helpers ----

    /**
     * Convert this store's URI to the protocol path string.
     * URI: oct://sessionId/sharedRoot/relative/path
     * Protocol path: sharedRoot/relative/path (authority-less)
     */
    private String toOctPath() {
        String path = uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private String sessionId() {
        return uri.getAuthority();
    }

    private String hostPeerId() {
        CollaborationInstance instance = resolveInstance();
        if (instance == null) return "broadcast";
        return instance.host != null ? instance.host.id : "broadcast";
    }

    private CollaborationInstance resolveInstance() {
        SessionService svc = SessionService.getInstance();
        if (svc == null) return null;
        return svc.getAllInstances().values().stream()
            .filter(i -> i.sessionData.roomId.equals(sessionId()))
            .findFirst().orElse(null);
    }

    private FileSystemService getFileSystemService() throws CoreException {
        CollaborationInstance instance = resolveInstance();
        if (instance == null) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "No active OCT session for id: " + sessionId()));
        }
        return (FileSystemService) instance.remoteInterface;
    }

    private OCTService getOctService() {
        CollaborationInstance instance = resolveInstance();
        if (instance == null) return null;
        // OCTService is the combined proxy — cast safely
        if (instance.remoteInterface instanceof OCTService svc) return svc;
        return null;
    }

    /** Block on a CompletableFuture with timeout, translating exceptions to CoreException. */
    private <T> T rpc(CompletableFuture<T> future, IProgressMonitor monitor) throws CoreException {
        if (future == null) return null;
        try {
            return future.get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "OCT RPC timed out for " + uri, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, "Interrupted", e));
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
                "OCT RPC failed for " + uri + ": " + e.getMessage(), e));
        }
    }

    private static URI normalize(URI uri) {
        try {
            String path = uri.getPath();
            if (path == null) path = "/";
            return new URI(uri.getScheme(), uri.getAuthority(), path, null, null);
        } catch (URISyntaxException e) {
            return uri;
        }
    }
}
