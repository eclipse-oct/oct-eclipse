/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.io.IOException;
import java.util.Base64;

import org.eclipse.oct.internal.protocol.FileChangeEventType;
import org.eclipse.oct.internal.protocol.FileContent;
import org.eclipse.oct.internal.protocol.FileType;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Gson TypeAdapter that wraps binary payloads as:
 * {"type":"binaryData","data":"<base64(msgpack(value))>"}
 *
 *
 */
public class BinaryDataAdapter<T> extends TypeAdapter<T> {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new MessagePackFactory());

	private final Class<T> type;

	public BinaryDataAdapter(Class<T> type) {
		this.type = type;
	}

	@Override
	public void write(JsonWriter out, T value) throws IOException {
		byte[] msgpackBytes = OBJECT_MAPPER.writeValueAsBytes(value);
		String base64 = Base64.getEncoder().encodeToString(msgpackBytes);

		out.beginObject();
		out.name("type").value("binaryData");
		out.name("data").value(base64);
		out.endObject();
	}

	@Override
	public T read(JsonReader in) throws IOException {
		String data = null;
		in.beginObject();
		while (in.hasNext()) {
			String fieldName = in.nextName();
			if ("data".equals(fieldName)) {
				data = in.nextString();
			} else {
				in.skipValue();
			}
		}
		in.endObject();

		if (data == null) {
			throw new IOException("BinaryData missing 'data' field");
		}

		byte[] decoded = Base64.getDecoder().decode(data);
		return OBJECT_MAPPER.readValue(decoded, type);
	}

	/** Register adapters for all binary-encoded protocol types. */
	public static void registerAll(com.google.gson.GsonBuilder builder) {
		builder.registerTypeAdapter(FileContent.class, new BinaryDataAdapter<>(FileContent.class));
		// FileType is encoded on the wire as its numeric protocol value
		// (1=File, 2=Directory, 64=SymbolicLink). Gson's default enum handling
		// would (de)serialize by name, so directories returned by a non-Eclipse
		// host would fail to parse and be treated as null/non-directories.
		builder.registerTypeAdapter(FileType.class, new TypeAdapter<FileType>() {
			@Override
			public void write(JsonWriter out, FileType value) throws IOException {
				if (value == null) {
					out.nullValue();
				} else {
					out.value(value.getValue());
				}
			}

			@Override
			public FileType read(JsonReader in) throws IOException {
				if (in.peek() == JsonToken.NULL) {
					in.nextNull();
					return null;
				}
				return FileType.fromValue(in.nextInt());
			}
		});
		// Same for FileChangeEventType (Create=0, Update=1, Delete=2). Without
		// this, host saves are broadcast as "Update" and VS Code ignores them.
		builder.registerTypeAdapter(FileChangeEventType.class, new TypeAdapter<FileChangeEventType>() {
			@Override
			public void write(JsonWriter out, FileChangeEventType value) throws IOException {
				if (value == null) {
					out.nullValue();
				} else {
					out.value(value.getValue());
				}
			}

			@Override
			public FileChangeEventType read(JsonReader in) throws IOException {
				if (in.peek() == JsonToken.NULL) {
					in.nextNull();
					return null;
				}
				return FileChangeEventType.fromValue(in.nextInt());
			}
		});
	}
}
