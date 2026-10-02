/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.util;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * UI-thread dispatch that survives workbench shutdown.
 * <p>
 * {@code Display.getDefault()} <em>creates</em> a display when none exists
 * instead of returning null, and on macOS that fails with
 * {@code SWTException: Invalid thread access} unless it happens on the main
 * thread. Inbound JSON-RPC notifications run on the reader thread and keep
 * arriving while the workbench tears down — closing the guest Eclipse makes
 * the service process send {@code sessionClosed} right as the display goes
 * away — so every dispatch off the UI thread has to look the display up
 * without ever creating one, and drop the work once there is none left.
 */
public final class UIThread {

	private UIThread() {
	}

	/**
	 * The live display, or {@code null} once the workbench is gone. Never
	 * creates one, and is safe to call from any thread.
	 */
	public static Display display() {
		Display display = Display.getCurrent();
		if (display == null && PlatformUI.isWorkbenchRunning()) {
			display = PlatformUI.getWorkbench().getDisplay();
		}
		return display != null && !display.isDisposed() ? display : null;
	}

	/** Runs on the UI thread, or drops the work if the display is gone. */
	public static void asyncExec(Runnable runnable) {
		dispatch(display -> display.asyncExec(runnable));
	}

	/**
	 * Runs on the UI thread and waits for it, or drops the work if the
	 * display is gone. An exception thrown by {@code runnable} still
	 * propagates — only a display disposed mid-dispatch is swallowed.
	 */
	public static void syncExec(Runnable runnable) {
		dispatch(display -> display.syncExec(runnable));
	}

	/** Runs on the UI thread after a delay, or drops the work if the display is gone. */
	public static void timerExec(int milliseconds, Runnable runnable) {
		dispatch(display -> display.timerExec(milliseconds, runnable));
	}

	private static void dispatch(Consumer<Display> target) {
		Display display = display();
		if (display == null) {
			return;
		}
		try {
			target.accept(display);
		} catch (SWTException e) {
			// The display can still be disposed between the lookup above and
			// the dispatch. Anything else — notably ERROR_FAILED_EXEC, which
			// is how syncExec reports an exception thrown by the runnable —
			// is a real failure and must not be hidden here.
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) {
				throw e;
			}
		}
	}
}
