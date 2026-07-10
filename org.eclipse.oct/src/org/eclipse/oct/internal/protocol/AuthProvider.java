/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class AuthProvider {
    public String name;
    public InfoMessage group;
    public InfoMessage label;
    public InfoMessage details;
    public String type;
    public String endpoint;
    public FormAuthProviderField[] fields;

    public AuthProvider() {}
}
