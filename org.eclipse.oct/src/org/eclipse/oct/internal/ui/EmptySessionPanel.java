/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import java.util.function.Consumer;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
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

		Link links = new Link(this, SWT.NONE);
		links.setText("<a href=\"org.eclipse.oct.hostSession\">Host a project</a>"
				+ "      <a href=\"org.eclipse.oct.joinSession\">Join with a code</a>");
		GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).grab(true, false).applyTo(links);
		links.addListener(SWT.Selection, e -> runCommand.accept(e.text));

		addDisposeListener(__ -> {
			if (titleFont != null && !titleFont.isDisposed()) {
				titleFont.dispose();
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
