/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * Represents a peer (participant) in an OCT session.
 */
public class Peer {
    public String id;
    public String host;
    public String name;
    public String email;
    public PeerMetaData metadata;

    public Peer() {}
}
