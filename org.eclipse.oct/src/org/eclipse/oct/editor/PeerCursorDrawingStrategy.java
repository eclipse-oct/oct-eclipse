/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationPainter.IDrawingStrategy;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Draws a 2px vertical caret at the peer's cursor and, while
 * {@link PeerAnnotation#isShowName()} is set, a VS Code-style name tag
 * (rounded pill in the peer color, bold dark text) above the caret — or
 * below it when there is no room on the first line.
 */
public class PeerCursorDrawingStrategy implements IDrawingStrategy {

	private static final int CARET_WIDTH = 2;
	private static final int TAG_PAD_X = 5;
	private static final int TAG_PAD_Y = 1;
	private static final int TAG_GAP = 2;
	private static final int TAG_RADIUS = 4;

	private final Map<RGB, Color> colorCache = new HashMap<>();
	private Font nameFont;
	private Color blackNameText;
	private Color whiteNameText;

	@Override
	public void draw(Annotation annotation, GC gc, StyledText textWidget, int offset, int length, Color color) {
		if (!(annotation instanceof PeerAnnotation peer)) {
			return;
		}

		Point loc;
		int lineHeight;
		try {
			loc = textWidget.getLocationAtOffset(offset);
			lineHeight = textWidget.getLineHeight(offset);
		} catch (IllegalArgumentException e) {
			return;
		}

		if (gc == null) {
			// Erase: invalidate caret + name-tag area (the tag sits outside the
			// zero-length caret range, so redrawRange alone would leave ghosts).
			Rectangle tag = nameTagBounds(textWidget, loc, lineHeight, peer.getPeerName());
			int x = Math.min(loc.x, tag.x);
			int y = Math.min(loc.y, tag.y);
			int w = Math.max(loc.x + CARET_WIDTH, tag.x + tag.width) - x + 2;
			int h = Math.max(loc.y + lineHeight, tag.y + tag.height) - y + 2;
			textWidget.redraw(x, y, w, h, false);
			return;
		}

		Color peerColor = getColor(textWidget, peer.getColor());
		gc.setBackground(peerColor);
		gc.fillRectangle(loc.x, loc.y, CARET_WIDTH, lineHeight);

		if (!peer.isShowName()) {
			return;
		}

		Font previousFont = gc.getFont();
		Color previousFg = gc.getForeground();
		try {
			gc.setFont(nameFont(textWidget));
			gc.setForeground(nameTextColor(textWidget, peer.getColor()));
			gc.setBackground(peerColor);

			Point extent = gc.textExtent(peer.getPeerName());
			Rectangle box = nameTagBounds(loc, lineHeight, extent);
			gc.fillRoundRectangle(box.x, box.y, box.width, box.height, TAG_RADIUS, TAG_RADIUS);
			gc.drawText(peer.getPeerName(), box.x + TAG_PAD_X, box.y + TAG_PAD_Y, true);
		} finally {
			gc.setFont(previousFont);
			gc.setForeground(previousFg);
		}
	}

	private Rectangle nameTagBounds(StyledText widget, Point loc, int lineHeight, String name) {
		Point extent;
		GC measure = new GC(widget);
		try {
			measure.setFont(nameFont(widget));
			extent = measure.textExtent(name != null ? name : "");
		} finally {
			measure.dispose();
		}
		return nameTagBounds(loc, lineHeight, extent);
	}

	private static Rectangle nameTagBounds(Point loc, int lineHeight, Point extent) {
		int width = extent.x + TAG_PAD_X * 2;
		int height = extent.y + TAG_PAD_Y * 2;
		int x = loc.x;
		int y = loc.y - height - TAG_GAP;
		if (y < 0) {
			y = loc.y + lineHeight + TAG_GAP;
		}
		return new Rectangle(x, y, width, height);
	}

	private Font nameFont(StyledText widget) {
		if (nameFont == null || nameFont.isDisposed()) {
			FontData fd = widget.getFont().getFontData()[0];
			fd.setHeight(Math.max(8, fd.getHeight() - 2));
			fd.setStyle(SWT.BOLD);
			nameFont = new Font(widget.getDisplay(), fd);
		}
		return nameFont;
	}

	/**
	 * Picks black or white text depending on the perceived brightness of
	 * {@code backgroundColor} (the peer's pill/tag fill), so the name stays
	 * readable regardless of how dark or light that peer's assigned color is
	 * (e.g. black text on a dark navy peer color was previously unreadable).
	 * Same approach VS Code Live Share / GitHub use for colored badges.
	 */
	private Color nameTextColor(StyledText widget, RGB backgroundColor) {
		if (isPerceivedLight(backgroundColor)) {
			if (blackNameText == null || blackNameText.isDisposed()) {
				blackNameText = new Color(widget.getDisplay(), 0, 0, 0);
			}
			return blackNameText;
		}
		if (whiteNameText == null || whiteNameText.isDisposed()) {
			whiteNameText = new Color(widget.getDisplay(), 255, 255, 255);
		}
		return whiteNameText;
	}

	/**
	 * ITU-R BT.601 perceptual luminance. Threshold slightly above the 0.5
	 * midpoint since black text reads a bit better than white at equal
	 * contrast, matching common badge-coloring heuristics.
	 */
	private static boolean isPerceivedLight(RGB rgb) {
		double luminance = (0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue) / 255.0;
		return luminance > 0.6;
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
		if (nameFont != null && !nameFont.isDisposed()) {
			nameFont.dispose();
		}
		nameFont = null;
		if (blackNameText != null && !blackNameText.isDisposed()) {
			blackNameText.dispose();
		}
		blackNameText = null;
		if (whiteNameText != null && !whiteNameText.isDisposed()) {
			whiteNameText.dispose();
		}
		whiteNameText = null;
	}
}
