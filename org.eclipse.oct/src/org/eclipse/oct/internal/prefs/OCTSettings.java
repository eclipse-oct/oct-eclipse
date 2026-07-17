/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.prefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Settings facade backed by IEclipsePreferences. 
 */
public class OCTSettings {

    private static final String PLUGIN_ID = "org.eclipse.oct";
    private static final String KEY_SERVER_URL = "defaultServerURL";
    private static final String KEY_STORED_TOKENS = "storedUserTokens";
    public static final String DEFAULT_SERVER_URL = "https://api.open-collab.tools/";

    private static OCTSettings INSTANCE;

    public static OCTSettings getInstance() {
        if (INSTANCE == null) INSTANCE = new OCTSettings();
        return INSTANCE;
    }

    private IEclipsePreferences getPrefs() {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID);
    }

    public String getDefaultServerURL() {
        return getPrefs().get(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setDefaultServerURL(String url) {
        getPrefs().put(KEY_SERVER_URL, url);
        flush();
    }

    public List<String> getStoredUserTokens() {
        String raw = getPrefs().get(KEY_STORED_TOKENS, "");
        if (raw.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    public void addStoredUserToken(String serverUrl) {
        List<String> tokens = getStoredUserTokens();
        if (!tokens.contains(serverUrl)) {
            tokens.add(serverUrl);
            saveStoredUserTokens(tokens);
        }
    }

    public void clearStoredUserTokens() {
        getPrefs().remove(KEY_STORED_TOKENS);
        flush();
    }

    private void saveStoredUserTokens(List<String> tokens) {
        getPrefs().put(KEY_STORED_TOKENS, String.join(",", tokens));
        flush();
    }

    private void flush() {
        try {
            getPrefs().flush();
        } catch (BackingStoreException e) {
            // ignore
        }
    }
}
