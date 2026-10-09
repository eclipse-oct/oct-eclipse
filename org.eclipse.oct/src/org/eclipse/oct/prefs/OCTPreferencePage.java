/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.prefs;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.ListEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Preference page for Open Collaboration Tools. Port of OCTSettings
 * configurable.
 */
public class OCTPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public OCTPreferencePage() {
		super(GRID);
		setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.oct"));
		setDescription("Open Collaboration Tools Settings");
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(OCTSettings.KEY_SERVER_URL, "Default server address:", getFieldEditorParent()));
		addField(new BooleanFieldEditor(OCTSettings.KEY_AUTO_FOLLOW_HOST, "Auto follow host on connnect", getFieldEditorParent()));
		addField(new ListEditor(OCTSettings.KEY_IGNORE_PATTERNS, "Exclude from sharing (glob patterns):",
				getFieldEditorParent()) {
			@Override
			protected String createList(String[] items) {
				return String.join(",", items);
			}

			@Override
			protected String[] parseString(String stringList) {
				return stringList.isBlank() ? new String[0] : stringList.split(",");
			}

			@Override
			protected String getNewInputObject() {
				InputDialog dialog = new InputDialog(getShell(), "Add Pattern",
						"Glob pattern (e.g. target, *.class):", "", null);
				return dialog.open() == Window.OK ? dialog.getValue() : null;
			}
		});
	}

	@Override
	public void init(IWorkbench workbench) {
	}
}
