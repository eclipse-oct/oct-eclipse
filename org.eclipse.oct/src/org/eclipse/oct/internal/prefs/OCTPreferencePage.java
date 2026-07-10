/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.prefs;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Preference page for Open Collaboration Tools. Port of OCTSettings configurable.
 */
public class OCTPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public OCTPreferencePage() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.oct"));
        setDescription("Open Collaboration Tools Settings");
    }

    @Override
    protected void createFieldEditors() {
        addField(new StringFieldEditor(
            "defaultServerURL",
            "Default server address:",
            getFieldEditorParent()
        ));
    }

    @Override
    public void init(IWorkbench workbench) {}
}
