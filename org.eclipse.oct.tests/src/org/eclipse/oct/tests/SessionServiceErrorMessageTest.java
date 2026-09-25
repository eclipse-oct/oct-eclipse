/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.oct.internal.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link SessionService#describeConnectionError}. Covers
 * the shape actually seen when the OCT server is unreachable: an
 * {@link ExecutionException} whose message carries the Node.js
 * {@code TypeError: fetch failed} text surfaced by the RPC layer.
 */
class SessionServiceErrorMessageTest {

	private static final String SERVER_URL = "https://oct.example.com";

	@Test
	@DisplayName("fetch failed is reported as an unreachable server")
	void fetchFailedIsReportedAsUnreachable() {
		Exception e = new ExecutionException(
				new RuntimeException("Request room/createRoom failed with message: Failed to create room: TypeError: fetch failed"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("connection refused nested in a cause is reported as an unreachable server")
	void connectionRefusedInCauseIsReportedAsUnreachable() {
		Exception e = new RuntimeException("wrapped", new RuntimeException("connect ECONNREFUSED 127.0.0.1:8100"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("timeout is reported as no response in time")
	void timeoutIsReportedAsNoResponse() {
		assertEquals("Server " + SERVER_URL + " did not respond in time.",
				SessionService.describeConnectionError(new TimeoutException(), SERVER_URL));
	}

	@Test
	@DisplayName("unrelated errors fall back to the raw exception message")
	void unrelatedErrorFallsBackToRawMessage() {
		Exception e = new RuntimeException("Room is full");
		assertEquals("Error: Room is full", SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("a cancelled connection attempt is reported as such, not as a raw exception")
	void cancellationIsReportedAsCancelled() {
		assertEquals("The connection attempt was cancelled.",
				SessionService.describeConnectionError(new CancellationException(), SERVER_URL));
	}

	@Test
	@DisplayName("DNS resolution failure (ENOTFOUND) nested in a cause is reported as an unreachable server")
	void enotfoundInCauseIsReportedAsUnreachable() {
		Exception e = new RuntimeException("wrapped", new RuntimeException("getaddrinfo ENOTFOUND oct.example.com"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("transient DNS failure (EAI_AGAIN) nested in a cause is reported as an unreachable server")
	void eaiAgainInCauseIsReportedAsUnreachable() {
		Exception e = new RuntimeException("wrapped", new RuntimeException("getaddrinfo EAI_AGAIN oct.example.com"));
		assertEquals("Server " + SERVER_URL + " is not reachable.",
				SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("an exception with no message anywhere in its cause chain falls back to an empty string")
	void nullMessageFallsBackToEmptyString() {
		Exception e = new RuntimeException((String) null);
		assertEquals("", SessionService.describeConnectionError(e, SERVER_URL));
	}

	@Test
	@DisplayName("a two-element cause cycle (A -> B -> A) does not hang the cause walk")
	void twoElementCauseCycleTerminates() {
		// initCause() only forbids a throwable from being its own cause, not a
		// cycle across two objects — exactly the shape a same-instance
		// self-reference guard (cause.getCause() == cause) fails to catch.
		RuntimeException a = new RuntimeException("a");
		RuntimeException b = new RuntimeException("b", a);
		a.initCause(b);
		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> assertEquals("Error: b", SessionService.describeConnectionError(b, SERVER_URL)));
	}
}
