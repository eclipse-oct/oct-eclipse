/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.tests.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * JVM-wide singleton that spawns the real {@code open-collaboration-server}
 * Node.js process for the duration of the test run.
 *
 * <p>Uses simple login (no OAuth) and a fixed dev JWT key so tests are
 * deterministic. The server listens on {@code localhost:8100} to match the
 * TypeScript integration test {@code service.process.test.ts}.
 *
 * <p>Started lazily on first {@link #ensureStarted()} call and reused across
 * every test class. Torn down via a JVM shutdown hook, so tycho-surefire can
 * run the entire test module against a single server instance.
 */
public final class OctTestServer {

	private static final Logger LOG = Logger.getLogger(OctTestServer.class.getName());

	// Bind explicitly on 127.0.0.1 (IPv4) — Node's default `localhost` binding on
	// macOS resolves to IPv6-only, which Java's HTTP stack refuses to connect to.
	private static final String HOST = "127.0.0.1";
	private static final int PORT = 8100;
	private static final String URL = "http://" + HOST + ":" + PORT;
	private static final String READY_MARKER = "listening on";
	private static final long STARTUP_TIMEOUT_SEC = 60;

	private static OctTestServer instance;
	private static volatile String cachedNodeExecutable;

	private final Process process;

	private OctTestServer(Process process) {
		this.process = process;
	}

	public static synchronized OctTestServer ensureStarted() {
		if (instance != null && instance.process.isAlive()) {
			return instance;
		}
		try {
			instance = start();
			Runtime.getRuntime().addShutdownHook(new Thread(OctTestServer::stopQuietly, "oct-test-server-shutdown"));
			return instance;
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException("Failed to start OCT test server", e);
		}
	}

	public String serverUrl() {
		return URL;
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	private static OctTestServer start() throws IOException, InterruptedException {
		Path serverScript = resolveServerScript();
		String nodeExecutable = resolveNodeExecutable();
		ProcessBuilder pb = new ProcessBuilder(nodeExecutable, serverScript.toString(), "--hostname=" + HOST);
		pb.environment().put("OCT_ACTIVATE_SIMPLE_LOGIN", "true");
		pb.environment().put("OCT_JWT_PRIVATE_KEY", "oct-eclipse-tests-key");
		pb.redirectErrorStream(true);
		LOG.info("Starting OCT server: " + nodeExecutable + " " + serverScript + " --hostname=" + HOST);
		Process p = pb.start();

		CountDownLatch ready = new CountDownLatch(1);
		Thread reader = new Thread(() -> {
			try (BufferedReader br = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					LOG.fine("[oct-server] " + line);
					if (line.contains(READY_MARKER)) {
						ready.countDown();
					}
				}
			} catch (IOException e) {
				LOG.log(Level.WARNING, "OCT server stream ended", e);
			}
		}, "oct-test-server-stdout");
		reader.setDaemon(true);
		reader.start();

		if (!ready.await(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IllegalStateException(
					"OCT server did not report '" + READY_MARKER + "' within " + STARTUP_TIMEOUT_SEC + "s");
		}
		LOG.info("OCT server is ready at " + URL);
		return new OctTestServer(p);
	}

	private static Path resolveServerScript() {
		String projectPath = System.getProperty("oct.project.path");
		if (projectPath == null || projectPath.isBlank()) {
			// Fall back to the layout used by the parent pom: sibling checkout of
			// open-collaboration-tools next to oct-eclipse.
			projectPath = new File(System.getProperty("user.dir"))
					.toPath()
					.resolve("../../open-collaboration-tools")
					.toAbsolutePath()
					.normalize()
					.toString();
		}
		Path script = Path.of(projectPath, "packages/open-collaboration-server/bin/server");
		if (!Files.isRegularFile(script)) {
			throw new IllegalStateException("open-collaboration-server entry point not found: " + script
					+ ". Set -Doct.project.path to the open-collaboration-tools checkout and run 'npm install && npm run build' there.");
		}
		return script;
	}

	/**
	 * Resolves an absolute path to the {@code node} executable.
	 *
	 * <p>GUI-launched Eclipse (Dock/Finder/Spotlight, and therefore the JDT JUnit
	 * launcher) does not inherit the {@code PATH} that a terminal shell builds up
	 * from {@code .zshrc}/{@code .zprofile} (nvm, volta, fnm, Homebrew, ...), so a
	 * plain {@code ProcessBuilder("node", ...)} that relies on the JVM's inherited
	 * environment fails with "No such file or directory" even though {@code node}
	 * works fine from a terminal. This resolves it explicitly instead:
	 * <ol>
	 * <li>{@code -Doct.node.executable=/path/to/node} VM argument, if set.</li>
	 * <li>Well-known install locations (Homebrew, system package, Volta, nvm).</li>
	 * <li>Asking the user's login shell (which sources the rc files) to resolve
	 * it via {@code command -v node}.</li>
	 * <li>Falling back to the bare {@code "node"} command, i.e. whatever the JVM's
	 * own inherited {@code PATH} happens to contain.</li>
	 * </ol>
	 */
	private static String resolveNodeExecutable() {
		if (cachedNodeExecutable != null) {
			return cachedNodeExecutable;
		}
		return cachedNodeExecutable = doResolveNodeExecutable();
	}

	private static String doResolveNodeExecutable() {
		String override = System.getProperty("oct.node.executable");
		if (override != null && !override.isBlank()) {
			if (!Files.isExecutable(Path.of(override))) {
				throw new IllegalStateException(
						"-Doct.node.executable=" + override + " does not point to an executable file");
			}
			LOG.info("Using node executable from -Doct.node.executable: " + override);
			return override;
		}

		for (Path candidate : wellKnownNodeLocations()) {
			if (Files.isExecutable(candidate)) {
				LOG.info("Resolved node executable at " + candidate);
				return candidate.toString();
			}
		}

		String viaLoginShell = resolveViaLoginShell();
		if (viaLoginShell != null) {
			LOG.info("Resolved node executable via login shell: " + viaLoginShell);
			return viaLoginShell;
		}

		LOG.warning("Could not resolve an absolute path to 'node'; falling back to the JVM's inherited PATH. "
				+ "If starting the server still fails, set -Doct.node.executable=/path/to/node as a VM argument "
				+ "in the launch config (run 'command -v node' in a terminal to find the path).");
		return "node";
	}

	private static List<Path> wellKnownNodeLocations() {
		List<Path> candidates = new ArrayList<>(List.of(
				Path.of("/opt/homebrew/bin/node"), // Homebrew on Apple Silicon
				Path.of("/usr/local/bin/node"), // Homebrew on Intel macOS / manual installs
				Path.of("/usr/bin/node"))); // Linux distro packages
		String home = System.getProperty("user.home");
		if (home != null && !home.isBlank()) {
			candidates.add(Path.of(home, ".volta/bin/node"));
			Path nvmVersions = Path.of(home, ".nvm/versions/node");
			if (Files.isDirectory(nvmVersions)) {
				try (Stream<Path> versions = Files.list(nvmVersions)) {
					versions.sorted(Comparator.comparing(Path::getFileName).reversed())
							.forEach(v -> candidates.add(v.resolve("bin/node")));
				} catch (IOException ignored) {
					// best-effort discovery only
				}
			}
		}
		return candidates;
	}

	private static String resolveViaLoginShell() {
		String shell = System.getenv("SHELL");
		if (shell == null || shell.isBlank()) {
			shell = "/bin/zsh";
		}
		try {
			ProcessBuilder pb = new ProcessBuilder(shell, "-lc", "command -v node");
			pb.redirectErrorStream(true);
			Process p = pb.start();
			List<String> lines = new ArrayList<>();
			try (BufferedReader br = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					lines.add(line);
				}
			}
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			// Login shells can print rc-file banners/warnings before the actual
			// output, so scan from the end for the first line that is a real,
			// executable path.
			for (int i = lines.size() - 1; i >= 0; i--) {
				String candidate = lines.get(i).trim();
				if (!candidate.isEmpty() && Files.isExecutable(Path.of(candidate))) {
					return candidate;
				}
			}
		} catch (IOException | InterruptedException | InvalidPathException e) {
			LOG.log(Level.FINE, "Failed to resolve node via login shell " + shell, e);
		}
		return null;
	}

	private static synchronized void stopQuietly() {
		if (instance == null) {
			return;
		}
		try {
			instance.process.destroy();
			if (!instance.process.waitFor(5, TimeUnit.SECONDS)) {
				instance.process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			instance.process.destroyForcibly();
		} finally {
			instance = null;
		}
	}
}
