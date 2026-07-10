/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui.commands;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.oct.internal.auth.AuthenticationService;

/**
 * Command handler for logging out of OCT (clears all stored tokens).
 */
public class LogoutHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        AuthenticationService.getInstance().logout();
        return null;
    }

    @Override
    public boolean isEnabled() {
        return !org.eclipse.oct.internal.prefs.OCTSettings.getInstance().getStoredUserTokens().isEmpty();
    }
}
