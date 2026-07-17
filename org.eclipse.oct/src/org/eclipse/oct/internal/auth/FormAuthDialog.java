/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.oct.internal.protocol.FormAuthProviderField;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * JFace dialog for form-based authentication.
 * Renders one labeled input field per {@link FormAuthProviderField} and
 * POSTs the collected data (plus the session token) as JSON to the provider
 * endpoint.
 */
public class FormAuthDialog extends TitleAreaDialog {

    private static final Logger LOG = Logger.getLogger(FormAuthDialog.class.getName());

    private final String serverUrl;
    private final String token;
    private final String endpoint;
    private final String providerName;
    private final FormAuthProviderField[] fields;

    /** Widgets keyed by field name, populated in {@link #createDialogArea}. */
    private final Map<String, Text> fieldWidgets = new LinkedHashMap<>();

    public FormAuthDialog(Shell parentShell, String serverUrl, String token,
                          String endpoint, String providerName, FormAuthProviderField[] fields) {
        super(parentShell);
        this.serverUrl = serverUrl;
        this.token = token;
        this.endpoint = endpoint;
        this.providerName = providerName != null ? providerName : "Login";
        this.fields = fields != null ? fields : new FormAuthProviderField[0];
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Open Collaboration Tools — " + providerName);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle(providerName + " Login");
        setMessage("Enter your credentials to authenticate.");

        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        layout.verticalSpacing = 8;
        container.setLayout(layout);

        for (FormAuthProviderField field : fields) {
            Label label = new Label(container, SWT.NONE);
            String labelText = (field.label != null && field.label.message != null)
                    ? field.label.message : field.name;
            label.setText(labelText + (field.required ? " *" : "") + ":");
            label.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

            boolean isPassword = field.name != null &&
                    (field.name.toLowerCase().contains("password") || field.name.toLowerCase().contains("secret"));
            int textStyle = SWT.BORDER | (isPassword ? SWT.PASSWORD : SWT.NONE);
            Text text = new Text(container, textStyle);
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            if (field.placeHolder != null && field.placeHolder.message != null) {
                text.setMessage(field.placeHolder.message);
            }
            fieldWidgets.put(field.name, text);
        }

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Login", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        // Collect form values
        Map<String, String> data = new LinkedHashMap<>();
        data.put("token", token);
        fieldWidgets.forEach((name, widget) -> {
            if (widget != null && !widget.isDisposed()) {
                data.put(name, widget.getText());
            }
        });

        // Build POST URL
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        String path = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        String postUrl = base + path;
        String jsonBody = toJson(data);

        // POST on a background thread — do not block the UI
        Thread postThread = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(15))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(postUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                LOG.info("Form auth POST completed [" + response.statusCode() + "] to: " + postUrl);
            } catch (IOException | InterruptedException e) {
                LOG.warning("Form auth POST failed: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }, "oct-form-auth");
        postThread.setDaemon(true);
        postThread.start();

        super.okPressed();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal JSON serialiser — avoids pulling in Gson just for one call. */
    private static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append('"').append(escapeJson(entry.getKey())).append("\":")
              .append('"').append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
