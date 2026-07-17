/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.io.IOException;
import java.util.List;

import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Fixes the Gson List→nested-array serialization bug in LSP4J.
 * 
 *
 * LSP4J/Gson wraps List params as [[p1,p2]] instead of [p1,p2].
 * This adapter converts List params to arrays before serialization.
 */
public class MessageTypeAdapter extends TypeAdapter<Message> {

    private final Gson gson;

    public MessageTypeAdapter(Gson gson) {
        this.gson = gson;
    }

    @Override
    public void write(JsonWriter out, Message value) throws IOException {
        if (value instanceof NotificationMessage msg) {
            if (msg.getParams() instanceof List<?> list) {
                msg.setParams(list.toArray());
            }
        } else if (value instanceof RequestMessage msg) {
            if (msg.getParams() instanceof List<?> list) {
                msg.setParams(list.toArray());
            }
        }
        gson.toJson(value, Message.class, out);
    }

    @Override
    public Message read(JsonReader in) throws IOException {
        return gson.fromJson(in, Message.class);
    }
}
