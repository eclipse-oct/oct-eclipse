/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * Represents a shared workspace in an OCT session.
 */
public class Workspace {
    public String name;
    public String[] folders;

    public Workspace() {}

    public Workspace(String name, String[] folders) {
        this.name = name;
        this.folders = folders;
    }
}
