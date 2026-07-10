/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Initializes default preference values.
 */
public class OCTPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.oct");
        store.setDefault("defaultServerURL", OCTSettings.DEFAULT_SERVER_URL);
    }
}
