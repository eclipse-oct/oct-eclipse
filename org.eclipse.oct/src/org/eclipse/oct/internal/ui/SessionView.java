/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.PeerColors;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

/**
 * Session view showing peers and follow controls.
 * Port of SessionViewFactory.kt.
 */
public class SessionView extends ViewPart {

    public static final String ID = "org.eclipse.oct.sessionView";

    private Composite root;
    private Composite peerList;
    private final List<Color> allocatedColors = new ArrayList<>();

    private Runnable unsubscribeSessionCreated;
    private Runnable unsubscribeSessionClosed;

    @Override
    public void createPartControl(Composite parent) {
        root = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(root);

        SessionService svc = SessionService.getInstance();
        if (svc != null) {
            unsubscribeSessionCreated = svc.onSessionCreated.onEvent(instance -> {
                instance.onPeersChanged.onEvent(__ -> refresh());
                refresh();
            });
            unsubscribeSessionClosed = svc.onSessionClosed.onEvent(__ -> refresh());
        }

        refresh();
    }

    private void refresh() {
        Display.getDefault().asyncExec(() -> {
            if (root == null || root.isDisposed()) return;

            // Dispose old content
            for (org.eclipse.swt.widgets.Control c : root.getChildren()) c.dispose();
            for (Color c : allocatedColors) c.dispose();
            allocatedColors.clear();

            SessionService svc = SessionService.getInstance();
            CollaborationInstance instance = findActiveInstance(svc);

            if (instance == null) {
                renderNoSession();
            } else {
                renderSession(instance);
            }

            root.layout(true, true);
        });
    }

    private void renderNoSession() {
        Label lbl = new Label(root, SWT.NONE);
        lbl.setText("No active collaboration session.");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(lbl);

        Button joinBtn = new Button(root, SWT.PUSH);
        joinBtn.setText("Join Session...");
        joinBtn.addListener(SWT.Selection, e -> {
            org.eclipse.ui.handlers.IHandlerService handlerSvc =
                getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
            try {
                handlerSvc.executeCommand("org.eclipse.oct.joinSession", null);
            } catch (Exception ex) {
                // ignore
            }
        });

        Button hostBtn = new Button(root, SWT.PUSH);
        hostBtn.setText("Host Session");
        hostBtn.addListener(SWT.Selection, e -> {
            org.eclipse.ui.handlers.IHandlerService handlerSvc =
                getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
            try {
                handlerSvc.executeCommand("org.eclipse.oct.hostSession", null);
            } catch (Exception ex) {
                // ignore
            }
        });
    }

    private void renderSession(CollaborationInstance instance) {
        String role = instance.isHost ? "Hosting" : "Collaborating";
        Label header = new Label(root, SWT.BOLD);
        header.setText("OCT Session — " + role + ": " + instance.sessionData.workspace.name);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);

        peerList = new Composite(root, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).applyTo(peerList);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(peerList);

        // Identity (self)
        if (instance.identity != null) {
            String selfRole = instance.isHost ? "(you • host)" : "(you)";
            renderPeerRow(peerList, instance.identity, selfRole, instance.peerColors, false);
        }

        // Host (for guests)
        if (!instance.isHost && instance.host != null) {
            renderPeerRow(peerList, instance.host, "(host)", instance.peerColors, true);
        }

        // Guests
        for (Peer guest : instance.guests) {
            if (instance.identity != null && guest.id.equals(instance.identity.id)) continue;
            renderPeerRow(peerList, guest, "", instance.peerColors, !instance.isHost);
        }
    }

    private void renderPeerRow(Composite parent, Peer peer, String suffix,
                                PeerColors peerColors, boolean showFollow) {
        // Color dot
        Label dot = new Label(parent, SWT.NONE);
        RGB rgb = peerColors.getColor(peer.id);
        Color c = new Color(parent.getDisplay(), rgb);
        allocatedColors.add(c);
        dot.setBackground(c);
        dot.setText("  ");

        // Name
        Label nameLbl = new Label(parent, SWT.NONE);
        String displayName = peer.name;
        if (peer.email != null && !peer.email.isBlank()) displayName += " (" + peer.email + ")";
        if (!suffix.isBlank()) displayName += " " + suffix;
        nameLbl.setText(displayName);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(nameLbl);

        // Follow button
        if (showFollow) {
            Button followBtn = new Button(parent, SWT.TOGGLE);
            followBtn.setText("Follow");
            final String peerId = peer.id;
            followBtn.addListener(SWT.Selection, e -> {
                CollaborationInstance inst = findActiveInstance(SessionService.getInstance());
                if (inst == null) return;
                if (followBtn.getSelection()) {
                    inst.getEditorManager().followPeer(peerId);
                } else {
                    inst.getEditorManager().stopFollowing();
                }
            });
        } else {
            new Label(parent, SWT.NONE); // placeholder
        }
    }

    private CollaborationInstance findActiveInstance(SessionService svc) {
        if (svc == null) return null;
        return svc.getAllInstances().values().stream().findFirst().orElse(null);
    }

    @Override
    public void setFocus() {
        root.setFocus();
    }

    @Override
    public void dispose() {
        if (unsubscribeSessionCreated != null) unsubscribeSessionCreated.run();
        if (unsubscribeSessionClosed != null) unsubscribeSessionClosed.run();
        for (Color c : allocatedColors) if (!c.isDisposed()) c.dispose();
        super.dispose();
    }
}
