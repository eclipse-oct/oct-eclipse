/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.fs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.oct.internal.WorkspaceFileSystemServiceHolder;
import org.eclipse.oct.internal.protocol.FileChange;
import org.eclipse.oct.internal.protocol.FileChangeEvent;
import org.eclipse.oct.internal.protocol.FileChangeEventType;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.FileSystemStat;
import org.eclipse.oct.internal.protocol.FileType;

/**
 * Host-side workspace file system service. Port of WorkspaceFileSystemService.kt.
 * Provides file operations over the Eclipse workspace API.
 */
public class WorkspaceFileSystemService implements WorkspaceFileSystemServiceHolder {

    private static final Logger LOG = Logger.getLogger(WorkspaceFileSystemService.class.getName());

    private final IProject project;

    public WorkspaceFileSystemService(IProject project) {
        this.project = project;
    }

    public IProject getProject() {
        return project;
    }

    public FileSystemStat stat(String path) {
        IResource resource = findMember(path);
        if (resource == null || !resource.exists()) return null;

        FileType type = getFileType(resource);
        long mtime = resource.getLocalTimeStamp();
        long size = 0;
        if (resource instanceof IFile file) {
            try {
                size = file.getLocation().toFile().length();
            } catch (Exception ignored) {}
        }
        return new FileSystemStat(type, mtime, mtime, size, null);
    }

    public FileContent readFile(String path) {
        IResource resource = findMember(path);
        if (!(resource instanceof IFile file) || !file.exists()) return null;
        try {
            byte[] bytes = file.getContents().readAllBytes();
            return new FileContent(bytes);
        } catch (CoreException | IOException e) {
            LOG.warning("Failed to read file: " + path + " - " + e.getMessage());
            return null;
        }
    }

    public Map<String, FileType> readDir(String path) {
        IResource resource = findMember(path);
        if (!(resource instanceof IContainer container) || !container.exists()) {
            return Map.of();
        }
        Map<String, FileType> result = new HashMap<>();
        try {
            for (IResource child : container.members()) {
                result.put(child.getName(), getFileType(child));
            }
        } catch (CoreException e) {
            LOG.warning("Failed to readDir: " + path + " - " + e.getMessage());
        }
        return result;
    }

    public CompletableFuture<Void> mkdir(String pathStr) {
        return runInWorkspace(monitor -> {
            IPath relPath = toRelativePath(pathStr);
            IFolder folder = project.getFolder(relPath);
            createFolderHierarchy(folder, monitor);
        });
    }

    public CompletableFuture<Void> writeFile(String pathStr, FileContent content) {
        return runInWorkspace(monitor -> {
            IPath relPath = toRelativePath(pathStr);
            IFile file = project.getFile(relPath);

            // Ensure parent folders exist
            if (!file.getParent().exists()) {
                if (file.getParent() instanceof IFolder parentFolder) {
                    createFolderHierarchy(parentFolder, monitor);
                }
            }

            ByteArrayInputStream stream = new ByteArrayInputStream(content.content != null ? content.content : new byte[0]);
            if (!file.exists()) {
                file.create(stream, true, monitor);
            } else {
                file.setContents(stream, true, false, monitor);
            }
        });
    }

    public CompletableFuture<Void> delete(String pathStr) {
        return runInWorkspace(monitor -> {
            IResource resource = findMember(pathStr);
            if (resource != null && resource.exists()) {
                resource.delete(true, monitor);
            }
        });
    }

    public CompletableFuture<Void> rename(String oldPath, String newPath) {
        return runInWorkspace(monitor -> {
            IResource resource = findMember(oldPath);
            if (resource == null || !resource.exists()) return;
            IPath newRelPath = toRelativePath(newPath);
            IPath newFullPath = project.getFullPath().append(newRelPath);
            resource.move(newFullPath, true, monitor);
        });
    }

    /**
     * Finds a resource relative to the project by path (which may include the project name as prefix).
     */
    public IResource findMember(String path) {
        IPath relPath = toRelativePath(path);
        if (relPath.isEmpty()) return project;
        return project.findMember(relPath);
    }

    private IPath toRelativePath(String path) {
        // Strip leading project name if present
        String projectName = project.getName();
        if (path.startsWith(projectName + "/")) {
            path = path.substring(projectName.length() + 1);
        } else if (path.startsWith("/" + projectName + "/")) {
            path = path.substring(projectName.length() + 2);
        } else if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return new Path(path);
    }

    private void createFolderHierarchy(IFolder folder, org.eclipse.core.runtime.IProgressMonitor monitor) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parentFolder && !parentFolder.exists()) {
            createFolderHierarchy(parentFolder, monitor);
        }
        folder.create(true, true, monitor);
    }

    private FileType getFileType(IResource resource) {
        if (resource.isLinked()) return FileType.SymbolicLink;
        return switch (resource.getType()) {
            case IResource.FILE -> FileType.File;
            case IResource.FOLDER, IResource.PROJECT -> FileType.Directory;
            default -> FileType.Unknown;
        };
    }

    private CompletableFuture<Void> runInWorkspace(IWorkspaceRunnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                ResourcesPlugin.getWorkspace().run(runnable, new NullProgressMonitor());
                future.complete(null);
            } catch (CoreException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
