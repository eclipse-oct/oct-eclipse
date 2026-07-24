/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog shown after a collaboration session is created, offering buttons to
 * copy the invitation code to the clipboard.
 */
public class SessionCreatedDialog extends TitleAreaDialog {

	private static final int COPY_ID_BTN = IDialogConstants.CLIENT_ID + 1;
	private static final int COPY_URL_BTN = IDialogConstants.CLIENT_ID + 2;

	private final String roomId;
	private final String serverUrl;

	public SessionCreatedDialog(Shell parentShell, String roomId, String serverUrl) {
		super(parentShell);
		this.roomId = roomId;
		this.serverUrl = serverUrl;
		setHelpAvailable(false);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Open Collaboration Tools");
	}

	@Override
	public void create() {
		super.create();
		setTitle("Session running");
		setMessage("Session ID: " + roomId + ".\nInvitation code was automatically written to clipboard.");

		// Auto-copy room ID to clipboard on open
		copyToClipboard(roomId);
		getShell().setDefaultButton(getButton(IDialogConstants.CANCEL_ID));
	}

	@Override
	protected Point getInitialSize() {
		Point initialSize = super.getInitialSize();
		initialSize.y = Math.min(convertVerticalDLUsToPixels(70), initialSize.y);
		return initialSize;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, COPY_ID_BTN, "Copy to Clipboard", false);
		createButton(parent, COPY_URL_BTN, "Copy with Server URL", false);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, true);
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == COPY_ID_BTN) {
			copyToClipboard(roomId);
			close();
		} else if (buttonId == COPY_URL_BTN) {
			String fullUrl = serverUrl + "#" + roomId;
			copyToClipboard(fullUrl);
			close();
		} else {
			super.buttonPressed(buttonId);
		}
	}

	private void copyToClipboard(String text) {
		Clipboard cb = new Clipboard(getShell().getDisplay());
		try {
			cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			cb.dispose();
		}
	}

	@Override
	protected boolean isResizable() {
		return false;
	}
}
