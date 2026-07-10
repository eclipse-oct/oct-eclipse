/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.oct.internal.auth.AuthenticationService;
import org.eclipse.oct.internal.fs.WorkspaceChangeListener;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Main OSGi bundle activator.
 * Initialises the SessionService, AuthenticationService, and resource listeners.
 *
 * @author Dennis Hübner - Initial contribution and API.
 */
public class Activator implements BundleActivator {

    public static final String PLUGIN_ID = "org.eclipse.oct";

    private static Activator instance;

    private SessionService sessionService;
    private WorkspaceChangeListener workspaceChangeListener;

    public static Activator getInstance() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        instance = this;

        // Bootstrap singletons
        AuthenticationService.getInstance();

        sessionService = new SessionService();
        SessionService.setInstance(sessionService);

        // Register workspace change listener for broadcasting file changes to guests
        workspaceChangeListener = new WorkspaceChangeListener(sessionService);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
            workspaceChangeListener, IResourceChangeEvent.POST_CHANGE);

        // Register project lifecycle listener (close/delete → close session)
        ResourcesPlugin.getWorkspace().addResourceChangeListener(event -> {
            if (event.getType() == IResourceChangeEvent.PRE_CLOSE
                    || event.getType() == IResourceChangeEvent.PRE_DELETE) {
                if (event.getResource() != null
                        && event.getResource() instanceof org.eclipse.core.resources.IProject project) {
                    if (sessionService.hasOpenSession(project)) {
                        sessionService.closeCurrentSession(project);
                    }
                    sessionService.projectClosed(project);
                }
            }
        }, IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.PRE_DELETE);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (workspaceChangeListener != null) {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(workspaceChangeListener);
        }

        // Close all open sessions
        if (sessionService != null) {
            for (org.eclipse.core.resources.IProject project : sessionService.getAllInstances().keySet()) {
                try {
                    sessionService.closeCurrentSession(project);
                } catch (Exception ignored) {}
            }
        }

        SessionService.setInstance(null);
        instance = null;
    }
}
