/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import org.eclipse.jface.text.source.Annotation;
import org.eclipse.swt.graphics.RGB;

/**
 * Annotation carrying peer cursor or selection state, plus the peer's color.
 * Used by PeerCursorDrawingStrategy and PeerSelectionDrawingStrategy.
 */
public class PeerAnnotation extends Annotation {

    public static final String TYPE_CURSOR = "org.eclipse.oct.peerCursor";
    public static final String TYPE_SELECTION = "org.eclipse.oct.peerSelection";

    private final String peerId;
    private final RGB color;

    public PeerAnnotation(String type, String peerId, RGB color) {
        super(type, false, peerId);
        this.peerId = peerId;
        this.color = color;
    }

    public String getPeerId() { return peerId; }
    public RGB getColor() { return color; }
}
