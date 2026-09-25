/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.auth;

import java.net.URI;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * An SWT Browser dialog used as a fallback/embedded login flow.
 */
public class LoginBrowserDialog extends Dialog {

	private final String url;

	/** May be non-null after show() if a redirect delivered a token. */
	private String receivedToken;

	/** Set once the SWT Browser widget failed to initialize; drives the manual-token fallback UI. */
	private boolean browserUnavailable;

	private Text tokenText;

	private Button okButton;

	public LoginBrowserDialog(Shell parentShell, String url) {
		super(parentShell);
		this.url = url;
		setShellStyle(SWT.CLOSE | SWT.RESIZE | SWT.TITLE | SWT.APPLICATION_MODAL);
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText("Open Collaboration Tools — Login");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite composite = (Composite) super.createDialogArea(parent);
		composite.setLayout(new GridLayout(1, false));

		try {
			Browser browser = new Browser(composite, SWT.NONE);
			browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

			// Listen for URL changes so we can detect authentication callbacks
			browser.addLocationListener(new LocationAdapter() {
				@Override
				public void changed(LocationEvent event) {
					String location = event.location;
					// Detect token delivered as query parameter
					if (location != null && location.contains("auth-token=")) {
						try {
							URI uri = URI.create(location);
							String query = uri.getQuery();
							if (query != null) {
								for (String param : query.split("&")) {
									if (param.startsWith("auth-token=")) {
										receivedToken = param.substring("auth-token=".length());
										break;
									}
								}
							}
						} catch (Exception ignored) {
						}
						if (receivedToken != null) {
							browser.getDisplay().asyncExec(() -> okPressed());
						}
					}
				}
			});

			browser.setUrl(url);
		} catch (SWTError e) {
			// SWT Browser not available — show a plain label with the URL and let the
			// user paste back a token obtained by completing the login in an external browser
			browserUnavailable = true;

			Label label = new Label(composite, SWT.WRAP);
			label.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
			label.setText("Browser not available.\nPlease open the following URL in your browser, log in, "
					+ "then paste the token it gives you below:\n\n" + url);

			tokenText = new Text(composite, SWT.BORDER);
			tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			tokenText.addModifyListener((ModifyEvent event) -> {
				if (okButton != null) {
					okButton.setEnabled(!tokenText.getText().isBlank());
				}
			});
		}

		return composite;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		if (browserUnavailable) {
			okButton = createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
			okButton.setEnabled(false);
			createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		} else {
			createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
		}
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == IDialogConstants.OK_ID && tokenText != null) {
			receivedToken = tokenText.getText().trim();
		}
		close();
	}

	@Override
	protected Point getInitialSize() {
		return new Point(960, 720);
	}

	/**
	 * Returns a token received via URL redirect during the browser session, or
	 * null.
	 */
	public String getReceivedToken() {
		return receivedToken;
	}
}
