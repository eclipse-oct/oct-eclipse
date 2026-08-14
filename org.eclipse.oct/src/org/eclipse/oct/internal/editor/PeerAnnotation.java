/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.editor;

import org.eclipse.jface.text.source.Annotation;
import org.eclipse.swt.graphics.RGB;

/**
 * Annotation carrying peer cursor or selection state, plus the peer's color
 * and display name (used by {@link PeerCursorDrawingStrategy} for the VS
 * Code-style name tag).
 */
public class PeerAnnotation extends Annotation {

	public static final String TYPE_CURSOR = "org.eclipse.oct.peerCursor";
	public static final String TYPE_SELECTION = "org.eclipse.oct.peerSelection";

	private final String peerId;
	private final String peerName;
	private final RGB color;
	private volatile boolean showName;

	public PeerAnnotation(String type, String peerId, RGB color) {
		this(type, peerId, peerId, color, false);
	}

	public PeerAnnotation(String type, String peerId, String peerName, RGB color, boolean showName) {
		super(type, false, peerName != null ? peerName : peerId);
		this.peerId = peerId;
		this.peerName = peerName != null && !peerName.isBlank() ? peerName : peerId;
		this.color = color;
		this.showName = showName;
	}

	public String getPeerId() {
		return peerId;
	}

	public String getPeerName() {
		return peerName;
	}

	public RGB getColor() {
		return color;
	}

	public boolean isShowName() {
		return showName;
	}

	public void setShowName(boolean showName) {
		this.showName = showName;
	}
}
