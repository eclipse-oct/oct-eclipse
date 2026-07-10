/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationPainter.IDrawingStrategy;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;

/**
 * Draws a 2px vertical caret bar at the peer's cursor position.
 * Port of PeerCaretHighlighterRenderer from CursorRenderer.kt.
 */
public class PeerCursorDrawingStrategy implements IDrawingStrategy {

    /** Cache of SWT Colors keyed by RGB (disposed on editor close). */
    private final Map<RGB, Color> colorCache = new HashMap<>();

    @Override
    public void draw(Annotation annotation, GC gc, StyledText textWidget,
                     int offset, int length, Color color) {
        if (gc == null) {
            // erase — repaint the background
            textWidget.redrawRange(offset, length, true);
            return;
        }

        if (!(annotation instanceof PeerAnnotation peer)) return;

        Color peerColor = getColor(textWidget, peer.getColor());
        try {
            Point loc = textWidget.getLocationAtOffset(offset);
            int lineHeight = textWidget.getLineHeight(offset);
            gc.setBackground(peerColor);
            gc.fillRectangle(loc.x, loc.y, 2, lineHeight);
        } catch (IllegalArgumentException ignored) {
            // offset out of range; skip
        }
    }

    private Color getColor(StyledText widget, RGB rgb) {
        return colorCache.computeIfAbsent(rgb, r -> new Color(widget.getDisplay(), r));
    }

    /** Call when the editor closes to prevent SWT Color leaks. */
    public void dispose() {
        colorCache.values().forEach(c -> { if (!c.isDisposed()) c.dispose(); });
        colorCache.clear();
    }
}
