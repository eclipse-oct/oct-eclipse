/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight event bus.
 */
public class EventEmitter<T> {

	/**
	 * CopyOnWriteArrayList, not ArrayList: every emitter in this plugin is
	 * subscribed on one thread and fired on another. Views and command
	 * handlers register/unregister on the UI thread while {@code fire} runs on
	 * the JSON-RPC reader ({@code onPeersChanged}, {@code onPresenceChanged})
	 * or on an Eclipse {@code Job} worker ({@code onSessionCreated}); for
	 * {@code onAuthAborted} it is the other way round, subscribed from the
	 * room-creation {@code Job} and fired from a dialog on the UI thread.
	 * <p>
	 * A plain {@code ArrayList} had no happens-before edge between those
	 * threads, so a freshly registered listener could simply be missed — a
	 * Session view opened while a join was in flight would then sit stale
	 * until the next unrelated event. The defensive copy {@code fire} used to
	 * make did not help: {@code new ArrayList<>(listeners)} reads
	 * {@code elementData} and {@code size} as two separate unsynchronized
	 * reads, so an {@code add} that grew the backing array in between yielded
	 * a snapshot padded with trailing {@code null}s and a
	 * {@code NullPointerException} on the reader thread, while a concurrent
	 * {@code remove} could shift an element into view twice and invoke a
	 * listener twice.
	 */
	private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

	public Runnable onEvent(Consumer<T> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public void fire(T arg) {
		// No defensive copy needed: iterating a CopyOnWriteArrayList already
		// walks an immutable snapshot, so a listener that unsubscribes itself
		// (or registers another) from inside its own callback stays safe.
		for (Consumer<T> listener : listeners) {
			listener.accept(arg);
		}
	}
}
