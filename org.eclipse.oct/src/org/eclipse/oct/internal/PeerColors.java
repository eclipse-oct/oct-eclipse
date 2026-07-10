/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.graphics.RGB;

/**
 * Assigns a distinct color to each peer. Port of PeerColorService.kt.
 */
public class PeerColors {

    private static final RGB[] DEFAULT_COLORS = {
        new RGB(255, 255, 0),   // Yellow
        new RGB(0, 200, 0),     // Green
        new RGB(255, 165, 0),   // Orange
        new RGB(0, 0, 255),     // Blue
        new RGB(255, 0, 255),   // Magenta
        new RGB(0, 200, 200),   // Cyan
    };

    private final Map<String, RGB> colors = new HashMap<>();

    public RGB getColor(String peerId) {
        if (colors.containsKey(peerId)) {
            return colors.get(peerId);
        }
        if (colors.size() < DEFAULT_COLORS.length) {
            RGB color = DEFAULT_COLORS[colors.size()];
            colors.put(peerId, color);
            return color;
        }
        // Fallback: generate a deterministic color from the peerId hash
        int hash = peerId.hashCode();
        int r = (hash & 0xFF0000) >> 16;
        int g = (hash & 0x00FF00) >> 8;
        int b = hash & 0x0000FF;
        RGB generated = new RGB(Math.max(r, 80), Math.max(g, 80), Math.max(b, 80));
        colors.put(peerId, generated);
        return generated;
    }
}
