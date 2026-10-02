/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
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

	/**
	 * Sizes to the shell's own preferred height, bypassing the floor that
	 * {@code TitleAreaDialog.getInitialSize()} applies.
	 * <p>
	 * That floor is {@code MIN_DIALOG_HEIGHT} — 150 dialog units, so roughly
	 * 19x the font height — and it exists for dialogs that put real content
	 * into the work area. This one leaves the work area empty: everything it
	 * shows lives in the title area and the button bar. The floor therefore
	 * only adds dead space below the separator, which is why this method used
	 * to cap the height at 70 dialog units instead. A cap is the wrong tool
	 * though: a dialog unit scales purely with the font height, while the
	 * height actually needed is mostly fixed (shell trim, margins, button
	 * padding), so below roughly a 16px dialog font the cap undercut the real
	 * requirement and cut the button bar off — unrecoverably, since this
	 * dialog is not resizable.
	 * <p>
	 * {@code Window.getInitialSize()} is exactly the unfloored preferred size
	 * ({@code shell.computeSize}), and it is correct at every font size. The
	 * width keeps {@code super}'s value: {@code MIN_DIALOG_WIDTH} is welcome
	 * here, and that value still grows for an unusually long room id.
	 */
	@Override
	protected Point getInitialSize() {
		Point withMinimums = super.getInitialSize();
		Point preferred = getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
		return new Point(withMinimums.x, preferred.y);
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
