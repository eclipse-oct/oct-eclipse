/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * Session data returned after creating or joining a room.
 */
public class SessionData {
    public String roomId;
    public String roomToken;
    public String authToken;
    public Workspace workspace;

    public SessionData() {}
}
