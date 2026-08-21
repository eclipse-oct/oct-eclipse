/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.ui.commands;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.oct.internal.SessionService;
import org.eclipse.swt.widgets.Display;

/**
 * Re-evaluates command enablement when a session starts or ends so toolbar
 * icons (Host / Join / Close / …) update immediately.
 */
abstract class OctSessionHandler extends AbstractHandler {

	private final List<Runnable> unsubs = new ArrayList<>();
	private boolean hooked;

	protected OctSessionHandler() {
		hook();
	}

	protected static boolean hasOpenSession() {
		SessionService svc = SessionService.getInstance();
		return svc != null && !svc.getAllInstances().isEmpty();
	}

	@Override
	public final boolean isEnabled() {
		hook();
		return computeEnabled();
	}

	protected abstract boolean computeEnabled();

	private void hook() {
		if (hooked) {
			return;
		}
		SessionService svc = SessionService.getInstance();
		if (svc == null) {
			return;
		}
		hooked = true;
		unsubs.add(svc.onSessionCreated.onEvent(__ -> refreshEnabled()));
		unsubs.add(svc.onSessionClosed.onEvent(__ -> refreshEnabled()));
		refreshEnabled();
	}

	private void refreshEnabled() {
		Runnable update = () -> setBaseEnabled(computeEnabled());
		Display display = Display.getCurrent();
		if (display == null) {
			Display.getDefault().asyncExec(update);
		} else {
			update.run();
		}
	}

	@Override
	public void dispose() {
		for (Runnable u : unsubs) {
			u.run();
		}
		unsubs.clear();
		super.dispose();
	}
}
