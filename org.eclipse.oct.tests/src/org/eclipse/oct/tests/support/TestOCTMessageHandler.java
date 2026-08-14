/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.oct.internal.CollaborationInstance;
import org.eclipse.oct.internal.protocol.AuthMetadata;
import org.eclipse.oct.internal.protocol.ClientTextSelection;
import org.eclipse.oct.internal.protocol.InitData;
import org.eclipse.oct.internal.protocol.Peer;
import org.eclipse.oct.internal.protocol.TextDocumentInsert;
import org.eclipse.oct.internal.protocol.User;
import org.eclipse.oct.internal.rpc.OCTMessageHandler;
import org.eclipse.oct.internal.util.EventEmitter;

/**
 * Test-only {@link OCTMessageHandler} that replaces the two UI-coupled hooks
 * ({@code authentication} and {@code joinSessionRequest}) with deterministic,
 * headless behavior:
 *
 * <ul>
 *   <li>{@link #authentication(String, AuthMetadata)} POSTs to
 *       {@code /api/login/simple/} with a fixed test username instead of
 *       delegating to {@code AuthenticationService} (which would open SWT
 *       dialogs).</li>
 *   <li>{@link #joinSessionRequest(User)} returns a preconfigured accept/reject
 *       decision instead of opening a {@code MessageDialog}.</li>
 * </ul>
 *
 * <p>All other inbound notifications ({@code init}, {@code peerInfo},
 * {@code peerJoined}, awareness updates, {@code sessionClosed}, …) are
 * inherited unchanged so tests exercise the real production dispatch paths.
 * Tests can observe reception via {@link #initFuture()} and
 * {@link #latestPeer()}.
 */
public class TestOCTMessageHandler extends OCTMessageHandler {

	private static final Logger LOG = Logger.getLogger(TestOCTMessageHandler.class.getName());

	private final String serverUrlNormalized;
	private final String username;
	private volatile Function<User, Boolean> joinPolicy = user -> true;
	private final CompletableFuture<InitData> initFuture = new CompletableFuture<>();
	private final AtomicReference<Peer> latestPeer = new AtomicReference<>();
	private final CompletableFuture<TextSelectionEvent> firstTextSelection = new CompletableFuture<>();
	private final CompletableFuture<DocumentUpdateEvent> firstDocumentUpdate = new CompletableFuture<>();
	private final CompletableFuture<String> firstEditorOpened = new CompletableFuture<>();
	private final CompletableFuture<Peer> firstPeerLeft = new CompletableFuture<>();
	/**
	 * Every {@code awareness/updateDocument} received, in arrival order. A peer
	 * that's already watching a path (e.g. because it seeded it, or a prior
	 * openDocument/updateDocument call registered its own
	 * YjsNormalizedTextDocument wrapper — see {@code CollaborationInstance
	 * .getNormalizedDocument} in open-collaboration-service-process) can receive
	 * several updates before the one a test actually cares about (e.g. the
	 * initial content seed arrives before a live edit sent moments later), so
	 * {@link #firstDocumentUpdate()} alone isn't always the right assertion
	 * target — use this list (or {@code TestSync.awaitDocumentUpdate}) to find a
	 * specific one.
	 */
	private final List<DocumentUpdateEvent> documentUpdates = new CopyOnWriteArrayList<>();

	public TestOCTMessageHandler(String serverUrl, EventEmitter<CollaborationInstance> onSessionCreated,
			String username) {
		super(serverUrl, onSessionCreated);
		this.serverUrlNormalized = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
		this.username = username;
	}

	/** Set what {@link #joinSessionRequest} should return; default accepts. */
	public void setJoinPolicy(Function<User, Boolean> policy) {
		this.joinPolicy = policy;
	}

	/** Completes when the service process delivers the {@code init} notification. */
	public CompletableFuture<InitData> initFuture() {
		return initFuture;
	}

	/** Last peer info received (host peer id after handshake). */
	public Peer latestPeer() {
		return latestPeer.get();
	}

	/** Completes on the first {@code awareness/updateTextSelection} received. */
	public CompletableFuture<TextSelectionEvent> firstTextSelection() {
		return firstTextSelection;
	}

	/** Completes on the first {@code awareness/updateDocument} received. */
	public CompletableFuture<DocumentUpdateEvent> firstDocumentUpdate() {
		return firstDocumentUpdate;
	}

	/** Every {@code awareness/updateDocument} received so far, in arrival order. */
	public List<DocumentUpdateEvent> documentUpdates() {
		return documentUpdates;
	}

	/** Completes on the first {@code editorOpened} received. */
	public CompletableFuture<String> firstEditorOpened() {
		return firstEditorOpened;
	}

	/** Completes on the first {@code peerLeft} received. */
	public CompletableFuture<Peer> firstPeerLeft() {
		return firstPeerLeft;
	}

	// ---- Overridden hooks ----

	@Override
	public void authentication(String token, AuthMetadata metadata) {
		try {
			performSimpleLogin(token);
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Simple login failed for user " + username, e);
			throw new RuntimeException("Simple login failed", e);
		}
	}

	@Override
	public CompletableFuture<Boolean> joinSessionRequest(User user) {
		boolean accept = joinPolicy.apply(user);
		LOG.info("joinSessionRequest from '" + (user != null ? user.name : "?") + "' → " + accept);
		return CompletableFuture.completedFuture(accept);
	}

	@Override
	public void init(InitData initData) {
		initFuture.complete(initData);
		super.init(initData);
	}

	@Override
	public void peerInfo(Peer peer) {
		latestPeer.set(peer);
		super.peerInfo(peer);
	}

	@Override
	public void peerLeft(Peer peer) {
		firstPeerLeft.complete(peer);
		super.peerLeft(peer);
	}

	@Override
	public void updateTextSelection(String url, ClientTextSelection[] selections) {
		firstTextSelection.complete(new TextSelectionEvent(url, selections));
		super.updateTextSelection(url, selections);
	}

	@Override
	public void updateDocument(String url, TextDocumentInsert[] updates) {
		DocumentUpdateEvent event = new DocumentUpdateEvent(url, updates);
		documentUpdates.add(event);
		firstDocumentUpdate.complete(event);
		super.updateDocument(url, updates);
	}

	@Override
	public void editorOpened(String documentPath, String peerId) {
		firstEditorOpened.complete(documentPath);
		super.editorOpened(documentPath, peerId);
	}

	// ---- Event records ----

	public record TextSelectionEvent(String path, ClientTextSelection[] selections) {
	}

	public record DocumentUpdateEvent(String path, TextDocumentInsert[] updates) {
	}

	// ---- HTTP helper ----

	private void performSimpleLogin(String pollToken) throws IOException {
		String body = "{\"token\":" + jsonString(pollToken) + ",\"user\":" + jsonString(username) + "}";
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		URI uri = URI.create(serverUrlNormalized + "/api/login/simple/");
		HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
		try {
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(5_000);
			conn.setReadTimeout(10_000);
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setRequestProperty("Content-Length", Integer.toString(bodyBytes.length));
			try (OutputStream out = conn.getOutputStream()) {
				out.write(bodyBytes);
			}
			int status = conn.getResponseCode();
			if (status / 100 != 2) {
				String errBody = readAll(conn.getErrorStream());
				throw new IOException("Simple login HTTP " + status + ": " + errBody);
			}
			// Drain the input to allow connection reuse.
			readAll(conn.getInputStream());
		} finally {
			conn.disconnect();
		}
	}

	private static String readAll(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String jsonString(String raw) {
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			switch (c) {
			case '"' -> sb.append("\\\"");
			case '\\' -> sb.append("\\\\");
			case '\n' -> sb.append("\\n");
			case '\r' -> sb.append("\\r");
			case '\t' -> sb.append("\\t");
			default -> {
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
			}
		}
		sb.append("\"");
		return sb.toString();
	}
}
