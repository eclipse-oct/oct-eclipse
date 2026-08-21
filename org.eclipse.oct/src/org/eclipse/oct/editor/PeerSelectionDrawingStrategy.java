/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

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
 * Fills the selection range with a semi-transparent peer color.
 */
public class PeerSelectionDrawingStrategy implements IDrawingStrategy {

	private final Map<RGB, Color> colorCache = new HashMap<>();

	@Override
	public void draw(Annotation annotation, GC gc, StyledText textWidget, int offset, int length, Color color) {
		if (gc == null) {
			textWidget.redrawRange(offset, length, true);
			return;
		}
		if (!(annotation instanceof PeerAnnotation peer) || length <= 0) {
			return;
		}

		Color peerColor = getColor(textWidget, peer.getColor());
		int savedAlpha = gc.getAlpha();
		try {
			gc.setAlpha(50);
			gc.setBackground(peerColor);

			// Fill each character position
			try {
				Point start = textWidget.getLocationAtOffset(offset);
				Point end = textWidget.getLocationAtOffset(offset + length);
				int lineHeight = textWidget.getLineHeight(offset);

				if (start.y == end.y) {
					// Single line
					gc.fillRectangle(start.x, start.y, end.x - start.x, lineHeight);
				} else {
					// First line: start.x → right edge
					int width = textWidget.getClientArea().width;
					gc.fillRectangle(start.x, start.y, width - start.x, lineHeight);
					// Middle lines (if any)
					for (int y = start.y + lineHeight; y < end.y; y += lineHeight) {
						gc.fillRectangle(0, y, width, lineHeight);
					}
					// Last line: 0 → end.x
					gc.fillRectangle(0, end.y, end.x, lineHeight);
				}
			} catch (IllegalArgumentException ignored) {
			}
		} finally {
			gc.setAlpha(savedAlpha);
		}
	}

	private Color getColor(StyledText widget, RGB rgb) {
		return colorCache.computeIfAbsent(rgb, r -> new Color(widget.getDisplay(), r));
	}

	public void dispose() {
		colorCache.values().forEach(c -> {
			if (!c.isDisposed()) {
				c.dispose();
			}
		});
		colorCache.clear();
	}
}
