/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lightweight event bus. Port of EventEmitter.kt.
 */
public class EventEmitter<T> {

    private final List<Consumer<T>> listeners = new ArrayList<>();

    public Runnable onEvent(Consumer<T> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void fire(T arg) {
        List<Consumer<T>> copy = new ArrayList<>(listeners);
        for (Consumer<T> listener : copy) {
            listener.accept(arg);
        }
    }
}
