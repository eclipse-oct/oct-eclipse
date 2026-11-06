/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.oct.protocol.ClientTextSelection;
import org.eclipse.oct.ui.PeerColors;
import org.eclipse.oct.util.UIThread;
import org.eclipse.swt.graphics.RGB;

/**
 * Draws peers' cursors and selections into a {@link TrackedEditor}'s peer
 * annotation model, and hides their name tags again shortly after.
 *
 * <p>
 * All methods must run on the UI thread — they touch annotation models and
 * repaint widgets.
 */
public class PeerAnnotationRenderer {

	private static final int NAME_TAG_VISIBLE_MS = 1900;

	private final PeerColors peerColors;
	private Function<String, String> peerNameLookup = id -> id;

	/**
	 * Bumps on each selection update so a stale hide-timer cannot clear a fresh
	 * name tag.
	 */
	private final Map<TrackedEditor, Integer> nameTagEpoch = new IdentityHashMap<>();

	private final AtomicBoolean disposed = new AtomicBoolean(false);

	public PeerAnnotationRenderer(PeerColors peerColors) {
		this.peerColors = peerColors;
	}

	/**
	 * Resolves a peer id to the display name shown on the cursor name tag.
	 * Defaults to the id itself when unset (tests / peers not yet in init).
	 */
	public void setPeerNameLookup(Function<String, String> peerNameLookup) {
		this.peerNameLookup = peerNameLookup != null ? peerNameLookup : id -> id;
	}

	/**
	 * Replaces every peer annotation in {@code editor} with the ones described
	 * by {@code selections}, and arms the name-tag hide timer.
	 */
	public void render(TrackedEditor editor, ClientTextSelection[] selections) {
		int docLen = editor.document().getLength();

		List<Annotation> toRemove = new ArrayList<>();
		editor.peerModel().getAnnotationIterator().forEachRemaining(toRemove::add);

		Map<Annotation, Position> toAdd = new HashMap<>();
		if (selections != null) {
			for (ClientTextSelection sel : selections) {
				if (sel.peer == null) {
					continue;
				}
				RGB color = peerColors.getColor(sel.peer);
				String name = peerNameLookup.apply(sel.peer);

				int caretOffset = Math.min(sel.start, docLen);
				toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_CURSOR, sel.peer, name, color, true),
						new Position(caretOffset, 0));

				if (sel.end != null && sel.end != sel.start) {
					int start = Math.min(Math.min(sel.start, sel.end), docLen);
					int end = Math.min(Math.max(sel.start, sel.end), docLen);
					if (end > start) {
						toAdd.put(new PeerAnnotation(PeerAnnotation.TYPE_SELECTION, sel.peer, name, color, false),
								new Position(start, end - start));
					}
				}
			}
		}

		editor.peerModel().replaceAnnotations(toRemove.toArray(new Annotation[0]), toAdd);
		scheduleHideNameTags(editor);
	}

	/**
	 * Removes every {@link PeerAnnotation} belonging to {@code peerId} from
	 * {@code editor} and repaints. Used both for a departed peer and for a peer
	 * that switched to a different file — neither case ever arrives over the
	 * wire as an explicit empty update, so cleanup has to be driven locally.
	 */
	public void removePeer(TrackedEditor editor, String peerId) {
		List<Annotation> toRemove = new ArrayList<>();
		editor.peerModel().getAnnotationIterator().forEachRemaining(annotation -> {
			if (annotation instanceof PeerAnnotation peer && peerId.equals(peer.getPeerId())) {
				toRemove.add(annotation);
			}
		});
		if (toRemove.isEmpty()) {
			return;
		}
		editor.peerModel().replaceAnnotations(toRemove.toArray(new Annotation[0]), Map.of());
		editor.redraw();
	}

	/** Drops the hide-timer bookkeeping for an editor that is no longer tracked. */
	public void forget(TrackedEditor editor) {
		nameTagEpoch.remove(editor);
	}

	public void dispose() {
		disposed.set(true);
		nameTagEpoch.clear();
	}

	private void scheduleHideNameTags(TrackedEditor editor) {
		int epoch = nameTagEpoch.merge(editor, 1, Integer::sum);
		UIThread.timerExec(NAME_TAG_VISIBLE_MS, () -> {
			if (disposed.get() || !Integer.valueOf(epoch).equals(nameTagEpoch.get(editor))) {
				return;
			}
			editor.peerModel().getAnnotationIterator().forEachRemaining(annotation -> {
				if (annotation instanceof PeerAnnotation peer) {
					peer.setShowName(false);
				}
			});
			editor.redraw();
		});
	}
}
