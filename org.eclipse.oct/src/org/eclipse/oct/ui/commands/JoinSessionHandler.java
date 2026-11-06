/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler for joining an OCT collaboration session.
 */
public class JoinSessionHandler extends OctSessionHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);

		InputDialog dialog = new InputDialog(window.getShell(), "Join Collaboration Session",
				"Enter the room ID or URL:", "", JoinSessionHandler::validateRoomToken);

		if (dialog.open() == Window.OK) {
			String roomToken = dialog.getValue().trim();
			SessionService.getInstance().joinRoom(roomToken);
		}
		return null;
	}

	@Override
	protected boolean computeEnabled() {
		return SessionService.getInstance() != null && !hasOpenSession();
	}

	/**
	 * Room IDs are server-generated nanoids restricted to {@code [0-9A-Za-z]}
	 * (open-collaboration-server's {@code generateSecureId}); the VS Code
	 * extension's {@code RoomUri.parse} validates pasted input against the
	 * same alphabet ({@code ^[a-zA-Z0-9]+$}) rather than merely blacklisting
	 * characters known to break routing. This isn't a security boundary —
	 * any other client can send the server an arbitrary room id directly —
	 * but matching that whitelist still catches a mistake (e.g. a pasted
	 * filesystem path) before it goes out as the {@code room/joinRoom} path
	 * segment, where a bare {@code /} would otherwise double up with the
	 * route's own and come back as a raw-HTML 404.
	 */
	private static final Pattern ROOM_ID = Pattern.compile("^[a-zA-Z0-9]+$");

	public static String validateRoomToken(String input) {
		if (input == null || input.isBlank()) {
			return "Room ID cannot be empty";
		}
		String trimmed = input.trim();
		if (trimmed.contains("://")) {
			return validateRoomUrl(trimmed);
		}
		return ROOM_ID.matcher(trimmed).matches() ? null : "Room ID can only contain letters and digits";
	}

	/**
	 * Mirrors {@link SessionService#joinRoom}'s own URL parsing exactly (same
	 * {@link URI} constructor, same {@code getFragment()} check) so a value
	 * accepted here is guaranteed to carry a usable room id out the other
	 * end. Without this, a URL with no fragment, an unparsable URL, or a
	 * fragment containing e.g. a '/' all previously fell through
	 * {@code joinRoom}'s "not a valid URL, use token as-is" fallback and got
	 * sent to the server as the literal {@code room/joinRoom} path segment,
	 * producing the exact same raw-HTML-404 class of error this validator
	 * exists to prevent — just one step removed, after the dialog had
	 * already accepted it.
	 */
	private static String validateRoomUrl(String url) {
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return "Not a valid URL";
		}
		String fragment = uri.getFragment();
		if (fragment == null || fragment.isBlank()) {
			return "URL must include a room id after '#'";
		}
		return ROOM_ID.matcher(fragment).matches() ? null : "Room ID in the URL can only contain letters and digits";
	}
}
