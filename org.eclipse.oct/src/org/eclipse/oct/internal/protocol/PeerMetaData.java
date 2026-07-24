/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.protocol;

public class PeerMetaData {
	public EncryptionMetaData encryption;
	public CompressionMetaData compression;
}

class EncryptionMetaData {
	public String publicKey;
}

class CompressionMetaData {
	public String[] supported;
}
