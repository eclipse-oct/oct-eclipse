/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.ui.commands;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.SessionService;

/**
 * Command handler that toggles the "follow" mode for a peer.
 *
 * <p>
 * Optional parameter {@value #PARAM_PEER_ID}: follow that peer, or stop if
 * already following them. Without a parameter, a guest follows/unfollows the
 * host and a host toggles {@code followGuestSelection}.
 *
 * <p>
 * Registered in plugin.xml against command
 * {@code org.eclipse.oct.toggleFollow}. Typically invoked programmatically from
 * the session view with the peer id embedded as a command parameter.
 */
public class ToggleFollowHandler extends AbstractHandler {

	/** Command parameter id for the target peer. */
	public static final String PARAM_PEER_ID = "org.eclipse.oct.toggleFollow.peerId";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String peerId = event.getParameter(PARAM_PEER_ID);

		SessionService svc = SessionService.getInstance();
		if (svc == null) {
			return null;
		}

		CollaborationInstance instance = svc.getAllInstances().values().stream().findFirst().orElse(null);
		if (instance == null) {
			return null;
		}

		var editorManager = instance.getEditorManager();
		if (editorManager == null) {
			return null;
		}

		if (peerId == null || peerId.isBlank()) {
			if (instance.isHost) {
				editorManager.setFollowGuestSelection(!editorManager.isFollowGuestSelection());
			} else if (editorManager.getFollowingPeerId() != null) {
				editorManager.stopFollowing();
			} else if (instance.host != null) {
				editorManager.followPeer(instance.host.id);
			}
			return null;
		}

		if (peerId.equals(editorManager.getFollowingPeerId())) {
			editorManager.stopFollowing();
		} else {
			editorManager.followPeer(peerId);
		}

		return null;
	}

	@Override
	public boolean isEnabled() {
		SessionService svc = SessionService.getInstance();
		return svc != null && !svc.getAllInstances().isEmpty();
	}
}
