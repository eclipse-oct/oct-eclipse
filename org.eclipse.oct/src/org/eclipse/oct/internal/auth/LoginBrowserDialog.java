/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.auth;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * An SWT Browser dialog used as a fallback/embedded login flow.
 */
public class LoginBrowserDialog extends Dialog {

    private final String url;

    /** May be non-null after show() if a redirect delivered a token. */
    private String receivedToken;

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
            browser.addLocationListener(new org.eclipse.swt.browser.LocationAdapter() {
                @Override
                public void changed(org.eclipse.swt.browser.LocationEvent event) {
                    String location = event.location;
                    // Detect token delivered as query parameter
                    if (location != null && location.contains("auth-token=")) {
                        try {
                            java.net.URI uri = java.net.URI.create(location);
                            String query = uri.getQuery();
                            if (query != null) {
                                for (String param : query.split("&")) {
                                    if (param.startsWith("auth-token=")) {
                                        receivedToken = param.substring("auth-token=".length());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                        if (receivedToken != null) {
                            browser.getDisplay().asyncExec(() -> okPressed());
                        }
                    }
                }
            });

            browser.setUrl(url);
        } catch (SWTError e) {
            // SWT Browser not available — show a plain label with the URL
            Label label = new Label(composite, SWT.WRAP);
            label.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            label.setText("Browser not available.\nPlease open the following URL in your browser:\n\n" + url);
        }

        return composite;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        close();
    }

    @Override
    protected Point getInitialSize() {
        return new Point(960, 720);
    }

    /**
     * Returns a token received via URL redirect during the browser session, or null.
     */
    public String getReceivedToken() {
        return receivedToken;
    }
}
