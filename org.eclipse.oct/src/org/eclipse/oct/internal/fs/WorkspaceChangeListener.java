/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.fs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.protocol.FileChange;
import org.eclipse.oct.internal.protocol.FileChangeEvent;
import org.eclipse.oct.internal.protocol.FileChangeEventType;
import org.eclipse.oct.internal.rpc.FileSystemService;

/**
 * Listens for local workspace changes and broadcasts them to guests.
 * Port of OCTFileListener.kt.
 */
public class WorkspaceChangeListener implements IResourceChangeListener {

    private static final Logger LOG = Logger.getLogger(WorkspaceChangeListener.class.getName());

    private final SessionService sessionService;

    public WorkspaceChangeListener(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (event.getType() != IResourceChangeEvent.POST_CHANGE) return;
        if (event.getDelta() == null) return;

        Map<IProject, List<FileChange>> projectChanges = new HashMap<>();

        try {
            event.getDelta().accept(new IResourceDeltaVisitor() {
                @Override
                public boolean visit(IResourceDelta delta) throws CoreException {
                    IResource resource = delta.getResource();
                    if (resource.getType() == IResource.ROOT) return true;

                    IProject project = resource.getProject();
                    if (project == null) return false;

                    // Only care about hosted projects
                    CollaborationInstance instance = sessionService.getCollaborationInstance(project);
                    if (instance == null || !instance.isHost) return false;

                    String path = project.getName() + "/" + resource.getProjectRelativePath().toString();

                    List<FileChange> changes = switch (delta.getKind()) {
                        case IResourceDelta.ADDED -> List.of(new FileChange(FileChangeEventType.Create, path));
                        case IResourceDelta.REMOVED -> List.of(new FileChange(FileChangeEventType.Delete, path));
                        case IResourceDelta.CHANGED -> {
                            if ((delta.getFlags() & IResourceDelta.MOVED_FROM) != 0) {
                                String oldPath = project.getName() + "/" + delta.getMovedFromPath().removeFirstSegments(1).toString();
                                yield List.of(
                                    new FileChange(FileChangeEventType.Delete, oldPath),
                                    new FileChange(FileChangeEventType.Create, path)
                                );
                            } else if ((delta.getFlags() & IResourceDelta.CONTENT) != 0) {
                                // Content-only changes are handled via awareness/updateDocument
                                yield List.of();
                            } else {
                                yield List.of(new FileChange(FileChangeEventType.Update, path));
                            }
                        }
                        default -> List.of();
                    };

                    if (!changes.isEmpty()) {
                        projectChanges.computeIfAbsent(project, k -> new ArrayList<>()).addAll(changes);
                    }

                    return true;
                }
            });
        } catch (CoreException e) {
            LOG.warning("Error processing resource delta: " + e.getMessage());
        }

        for (Map.Entry<IProject, List<FileChange>> entry : projectChanges.entrySet()) {
            CollaborationInstance instance = sessionService.getCollaborationInstance(entry.getKey());
            if (instance != null && instance.isHost) {
                FileChangeEvent event2 = new FileChangeEvent(entry.getValue().toArray(new FileChange[0]));
                ((FileSystemService) instance.remoteInterface).change(event2, "broadcast");
            }
        }
    }
}
