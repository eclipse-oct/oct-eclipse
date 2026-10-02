/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.prefs;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Settings facade backed by IEclipsePreferences.
 */
public class OCTSettings {

	private static final String PLUGIN_ID = "org.eclipse.oct";
	static final String KEY_SERVER_URL = "defaultServerURL";
	static final String KEY_AUTO_FOLLOW_HOST = "autoFollowHost";
	private static final String KEY_STORED_TOKENS = "storedUserTokens";
	public static final String DEFAULT_SERVER_URL = "https://api.open-collab.tools/";
	public static final boolean DEFAULT_AUTO_FOLLOW_HOST = false;

	private static OCTSettings INSTANCE;

	public static OCTSettings getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new OCTSettings();
		}
		return INSTANCE;
	}

	private IEclipsePreferences getPrefs() {
		return InstanceScope.INSTANCE.getNode(PLUGIN_ID);
	}

	public String getDefaultServerURL() {
		return normalizeServerUrl(getPrefs().get(KEY_SERVER_URL, DEFAULT_SERVER_URL));
	}

	/** Used only for tests */
	public void setDefaultServerURL(String url) {
		getPrefs().put(KEY_SERVER_URL, normalizeServerUrl(url));
		flush();
	}

	/**
	 * Whether a guest starts out following the host. Reads the instance node
	 * with an explicit fallback rather than going through the default scope:
	 * {@code ScopedPreferenceStore} removes the key again whenever the user
	 * sets it back to the default, so "absent" has to mean
	 * {@link #DEFAULT_AUTO_FOLLOW_HOST}.
	 */
	public boolean isAutoFollowHost() {
		return getPrefs().getBoolean(KEY_AUTO_FOLLOW_HOST, DEFAULT_AUTO_FOLLOW_HOST);
	}

	/**
	 * Notifies on every change of the default server URL, however it was changed
	 * (the preference page writes the node directly, not through
	 * {@link #setDefaultServerURL(String)}). Returns a {@code Runnable} that
	 * unregisters the listener.
	 */
	public Runnable addServerUrlChangeListener(Consumer<String> listener) {
		IEclipsePreferences.IPreferenceChangeListener prefListener = event -> {
			if (KEY_SERVER_URL.equals(event.getKey())) {
				listener.accept(getDefaultServerURL());
			}
		};
		getPrefs().addPreferenceChangeListener(prefListener);
		return () -> getPrefs().removePreferenceChangeListener(prefListener);
	}

	/**
	 * Rewrite listen-any hosts ({@code 0.0.0.0}, {@code ::}) to loopback. Those
	 * addresses are valid for a server bind, but Java's HTTP/WebSocket clients
	 * cannot connect to them (unlike Node, which often remaps them).
	 */
	public static String normalizeServerUrl(String url) {
		if (url == null || url.isBlank()) {
			return url;
		}
		try {
			URI uri = URI.create(url.trim());
			String host = uri.getHost();
			if (host == null) {
				return url.trim();
			}
			if ("0.0.0.0".equals(host) || "::".equals(host)) {
				return new URI(uri.getScheme(), uri.getUserInfo(), "127.0.0.1", uri.getPort(), uri.getPath(),
						uri.getQuery(), uri.getFragment()).toString();
			}
			return uri.toString();
		} catch (Exception e) {
			return url.trim();
		}
	}

	public List<String> getStoredUserTokens() {
		String raw = getPrefs().get(KEY_STORED_TOKENS, "");
		if (raw.isBlank()) {
			return new ArrayList<>();
		}
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
