/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui;

import java.util.function.Consumer;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.oct.prefs.OCTSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

/**
 * Centered empty-state copy and host/join links.
 */
final class EmptySessionPanel extends Composite {

	private Font titleFont;
	private Runnable unsubscribeServerUrl;

	EmptySessionPanel(Composite parent, Consumer<String> runCommand) {
		super(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().spacing(0, 10).applyTo(this);
		setBackgroundMode(SWT.INHERIT_DEFAULT);

		Label title = new Label(this, SWT.CENTER);
		titleFont = bold(title);
		title.setFont(titleFont);
		title.setText("Ready to collaborate");
		GridDataFactory.fillDefaults().grab(true, false).applyTo(title);

		Label hint = new Label(this, SWT.CENTER | SWT.WRAP);
		hint.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));
		hint.setText("Share a project or drop in with a room code.");
		GridDataFactory.fillDefaults().grab(true, false).applyTo(hint);

		Composite serverRow = new Composite(this, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).spacing(4, 0).applyTo(serverRow);
		GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).grab(true, false).applyTo(serverRow);

		Label serverLabel = new Label(serverRow, SWT.NONE);
		serverLabel.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));
		serverLabel.setText("OCT Server:");

		Label serverUrl = new Label(serverRow, SWT.NONE);
		serverUrl.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
		serverUrl.setText(OCTSettings.getInstance().getDefaultServerURL());

		unsubscribeServerUrl = OCTSettings.getInstance().addServerUrlChangeListener(url -> getDisplay().asyncExec(() -> {
			if (serverUrl.isDisposed()) {
				return;
			}
			serverUrl.setText(url);
			layout(true, true);
		}));

		Link links = new Link(this, SWT.NONE);
		links.setText("<a href=\"org.eclipse.oct.hostSession\">Host a project</a>"
				+ "      <a href=\"org.eclipse.oct.joinSession\">Join with a code</a>");
		GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).grab(true, false).applyTo(links);
		links.addListener(SWT.Selection, e -> runCommand.accept(e.text));

		addDisposeListener(__ -> {
			if (titleFont != null && !titleFont.isDisposed()) {
				titleFont.dispose();
			}
			if (unsubscribeServerUrl != null) {
				unsubscribeServerUrl.run();
			}
		});
	}

	private static Font bold(Label label) {
		FontData[] data = label.getFont().getFontData();
		for (FontData fd : data) {
			fd.setStyle(SWT.BOLD);
			fd.setHeight(fd.getHeight() + 2);
		}
		return new Font(label.getDisplay(), data);
	}
}
