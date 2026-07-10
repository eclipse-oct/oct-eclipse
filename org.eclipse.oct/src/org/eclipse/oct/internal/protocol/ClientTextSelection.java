/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class ClientTextSelection {
    public String peer;
    public int start;
    public Integer end;
    public boolean isReversed;

    public ClientTextSelection() {}

    public ClientTextSelection(String peer, int start, Integer end, boolean isReversed) {
        this.peer = peer;
        this.start = start;
        this.end = end;
        this.isReversed = isReversed;
    }
}
