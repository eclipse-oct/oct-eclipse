/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.auth;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.oct.internal.prefs.OCTSettings;
import org.eclipse.oct.internal.protocol.AuthMetadata;
import org.eclipse.oct.internal.protocol.AuthProvider;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

/**
 * Handles authentication flows and token storage.
 * Port of AuthenticationService.kt.
 */
public class AuthenticationService {

    private static final Logger LOG = Logger.getLogger(AuthenticationService.class.getName());
    private static final String SECURE_PREFS_NODE = "org.eclipse.oct";
    private static final String TOKEN_KEY_PREFIX = "OCT-Auth-Token/";

    private static AuthenticationService INSTANCE;

    /** Currently open browser login dialog, closed when onAuthenticated fires. */
    private LoginBrowserDialog currentBrowserDialog;

    public static AuthenticationService getInstance() {
        if (INSTANCE == null) INSTANCE = new AuthenticationService();
        return INSTANCE;
    }

    /**
     * Called when the service process sends an {@code authentication} notification.
     * Shows a provider chooser when multiple providers are available, then dispatches
     * to the appropriate flow (form, web, or browser fallback).
     */
    public void authenticate(String serverUrl, String token, AuthMetadata metadata) {
        Display.getDefault().asyncExec(() -> {
            Shell shell = activeShell();

            // No metadata or no providers → open login-page URL
            if (metadata == null || metadata.providers == null || metadata.providers.length == 0) {
                if (metadata != null && metadata.loginPageUrl != null) {
                    openBrowserDialog(shell, metadata.loginPageUrl);
                }
                return;
            }

            if (metadata.providers.length == 1) {
                dispatchProvider(shell, serverUrl, token, metadata.providers[0], metadata);
            } else {
                // Multiple providers → show chooser dialog (port of JBPopupFactory chooser)
                List<String> names = Arrays.stream(metadata.providers)
                        .map(p -> p.name != null ? p.name : p.type)
                        .collect(Collectors.toList());

                ElementListSelectionDialog chooser = new ElementListSelectionDialog(shell, new LabelProvider());
                chooser.setTitle("Open Collaboration Tools");
                chooser.setMessage("Select an Authentication Option:");
                chooser.setElements(names.toArray());
                chooser.setMultipleSelection(false);

                if (chooser.open() == Dialog.OK && chooser.getFirstResult() != null) {
                    String selectedName = (String) chooser.getFirstResult();
                    AuthProvider selected = Arrays.stream(metadata.providers)
                            .filter(p -> selectedName.equals(p.name != null ? p.name : p.type))
                            .findFirst().orElse(null);
                    if (selected != null) {
                        dispatchProvider(shell, serverUrl, token, selected, metadata);
                    }
                }
            }
        });
    }

    /**
     * Dispatches to the correct auth flow for a single resolved provider.
     */
    private void dispatchProvider(Shell shell, String serverUrl, String token,
                                  AuthProvider provider, AuthMetadata metadata) {
        if ("form".equals(provider.type)) {
            handleFormAuth(shell, serverUrl, token, provider);
        } else if ("web".equals(provider.type)) {
            handleWebAuth(serverUrl, provider, token);
        } else {
            // Generic fallback: embedded browser or external browser
            if (metadata.loginPageUrl != null) {
                openBrowserDialog(shell, metadata.loginPageUrl);
            }
        }
    }

    /**
     * Opens a {@link FormAuthDialog} with one field per provider field and POSTs the result.
     */
    private void handleFormAuth(Shell shell, String serverUrl, String token, AuthProvider provider) {
        if (provider.fields == null || provider.fields.length == 0) {
            LOG.warning("Form auth provider has no fields: " + provider.name);
            return;
        }
        FormAuthDialog dialog = new FormAuthDialog(
                shell, serverUrl, token, provider.endpoint,
                provider.name, provider.fields);
        dialog.open();
    }

    /**
     * Opens the external browser to the web-auth URL (port of handleWebAuth / Desktop.browse).
     */
    private void handleWebAuth(String serverUrl, AuthProvider provider, String token) {
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        String path = provider.endpoint != null
                ? (provider.endpoint.startsWith("/") ? provider.endpoint.substring(1) : provider.endpoint)
                : "";
        String url = base + path + "?token=" + token;
        Program.launch(url);
    }

    /**
     * Opens the embedded SWT Browser dialog for the login-page URL.
     * Falls back to {@link Program#launch} when the SWT Browser is unavailable.
     */
    private void openBrowserDialog(Shell shell, String loginPageUrl) {
        try {
            if (currentBrowserDialog != null) {
                currentBrowserDialog.close();
                currentBrowserDialog = null;
            }
            currentBrowserDialog = new LoginBrowserDialog(shell, loginPageUrl);
            currentBrowserDialog.open();
            // Check if a token was received via redirect
            String received = currentBrowserDialog.getReceivedToken();
            if (received != null) {
                onAuthenticated(received, loginPageUrl);
            }
        } catch (Exception e) {
            // SWT Browser unavailable — fall back to external browser
            LOG.fine("SWT Browser unavailable, opening external browser: " + e.getMessage());
            Program.launch(loginPageUrl);
        }
    }

    /**
     * Stores the auth token after successful authentication and closes any open browser dialog.
     */
    public void onAuthenticated(String authToken, String serverUrl) {
        // Close embedded browser dialog if still open
        Display.getDefault().asyncExec(() -> {
            if (currentBrowserDialog != null) {
                currentBrowserDialog.close();
                currentBrowserDialog = null;
            }
        });
        try {
            ISecurePreferences prefs = getSecureNode();
            prefs.put(TOKEN_KEY_PREFIX + serverUrl, authToken, true /*encrypt*/);
            OCTSettings.getInstance().addStoredUserToken(serverUrl);
        } catch (StorageException e) {
            LOG.warning("Failed to store auth token: " + e.getMessage());
        }
    }

    /**
     * Retrieves the stored auth token for a server URL.
     */
    public String getAuthToken(String serverUrl) {
        try {
            ISecurePreferences prefs = getSecureNode();
            return prefs.get(TOKEN_KEY_PREFIX + serverUrl, null);
        } catch (StorageException e) {
            LOG.warning("Failed to read auth token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clears all stored auth tokens.
     */
    public void logout() {
        try {
            ISecurePreferences prefs = getSecureNode();
            prefs.removeNode();
            OCTSettings.getInstance().clearStoredUserTokens();
        } catch (Exception e) {
            LOG.warning("Failed to clear auth tokens: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ISecurePreferences getSecureNode() {
        return SecurePreferencesFactory.getDefault().node(SECURE_PREFS_NODE);
    }

    private static Shell activeShell() {
        try {
            return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        } catch (Exception e) {
            return Display.getDefault().getActiveShell();
        }
    }
}