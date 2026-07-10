/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

/**
 * Status bar contribution showing the current OCT session state.
 * Port of StatusBarSessionWidget.kt.
 */
public class StatusBarContribution extends WorkbenchWindowControlContribution {

    private Label label;
    private Runnable unsubCreated;
    private Runnable unsubClosed;

    @Override
    protected Control createControl(Composite parent) {
        label = new Label(parent, SWT.NONE);
        label.setText("");
        label.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        // Click → show the OCT Session view
        label.addListener(SWT.MouseUp, e -> openSessionView());

        SessionService svc = SessionService.getInstance();
        if (svc != null) {
            unsubCreated = svc.onSessionCreated.onEvent(__ -> refresh());
            unsubClosed = svc.onSessionClosed.onEvent(__ -> refresh());
        }

        refresh();
        return label;
    }

    private void refresh() {
        if (label == null || label.isDisposed()) return;
        label.getDisplay().asyncExec(() -> {
            if (label.isDisposed()) return;
            SessionService svc = SessionService.getInstance();
            if (svc == null || svc.getAllInstances().isEmpty()) {
                label.setText("");
                label.setToolTipText(null);
                return;
            }
            CollaborationInstance instance = svc.getAllInstances().values().iterator().next();
            String status = instance.isHost ? "OCT: Sharing" : "OCT: Collaborating";
            label.setText("  " + status + "  ");
            label.setToolTipText("Open Collaboration Tools — " + instance.sessionData.workspace.name);
        });
    }

    private void openSessionView() {
        try {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            if (page != null) {
                page.showView(SessionView.ID, null, IWorkbenchPage.VIEW_ACTIVATE);
            }
        } catch (PartInitException e) {
            // ignore — view simply doesn't open
        }
    }

    @Override
    public void dispose() {
        if (unsubCreated != null) unsubCreated.run();
        if (unsubClosed != null) unsubClosed.run();
        super.dispose();
    }
}
