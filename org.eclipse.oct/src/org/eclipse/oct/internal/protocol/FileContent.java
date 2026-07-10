/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

/**
 * Binary file content. Serialized/deserialized via BinaryDataAdapter (msgpack + base64).
 */
public class FileContent {
    public byte[] content;

    public FileContent() {}

    public FileContent(byte[] content) {
        this.content = content;
    }
}
