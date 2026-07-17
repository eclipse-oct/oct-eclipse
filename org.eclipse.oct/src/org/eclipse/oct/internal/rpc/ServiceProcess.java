/*
 * Copyright (c) 2026 TypeFox GmbH and others.
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.oct.internal.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.Platform;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.oct.internal.auth.AuthenticationService;

/**
 * Manages the oct-service-process executable lifecycle and the JSON-RPC
 * launcher.
 */
public class ServiceProcess implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(ServiceProcess.class.getName());
	private static final String EXECUTABLE_BASE = "oct-bin/oct-service-process";

	private final String serverUrl;
	private final List<BaseMessageHandler> messageHandlers;

	private Process currentProcess;
	private Launcher<BaseMessageHandler.BaseRemoteInterface> jsonRpc;
	private Path executablePath;

	public ServiceProcess(String serverUrl, List<BaseMessageHandler> messageHandlers) {
		this.serverUrl = serverUrl;
		this.messageHandlers = messageHandlers;
		startProcess();
	}

	@SuppressWarnings("unchecked")
	public <T extends BaseMessageHandler.BaseRemoteInterface> T getOctService() {
		if (jsonRpc == null) {
			throw new IllegalStateException("ServiceProcess is not initialized");
		}
		return (T) jsonRpc.getRemoteProxy();
	}

	private void startProcess() {
		if (executablePath == null) {
			try {
				extractExecutable();
			} catch (IOException e) {
				LOG.log(Level.SEVERE, "Failed to extract OCT service executable", e);
				return;
			}
		}

		try {
			String savedAuthToken = AuthenticationService.getInstance().getAuthToken(serverUrl);
			String tokenArg = (savedAuthToken != null) ? savedAuthToken : "";

			ProcessBuilder pb = new ProcessBuilder(executablePath.toString(), "--server-address=" + serverUrl,
					"--auth-token=" + tokenArg);
			pb.redirectErrorStream(false);
			currentProcess = pb.start();

			currentProcess.onExit().thenRun(() -> {
				try {
					byte[] err = currentProcess.getErrorStream().readAllBytes();
					byte[] out = currentProcess.getInputStream().readAllBytes();
					if (out.length > 0) {
						LOG.info("OCT service process logged:\n" + new String(out));
					}
					if (err.length > 0 || currentProcess.exitValue() != 0) {
						var message = "OCT service process exited with code (" + currentProcess.exitValue() + ")";
						LOG.log(Level.SEVERE, message + ":\n" + new String(err));
						throw new RuntimeException(message);
					}
				} catch (IOException ignored) {
				}
				currentProcess = null;
			});

			List<Class<?>> remoteInterfaces = new java.util.ArrayList<>();
			for (BaseMessageHandler h : messageHandlers)
				remoteInterfaces.add(h.getRemoteInterface());

			List<Object> localServices = new java.util.ArrayList<>(messageHandlers);

			@SuppressWarnings({ "unchecked", "rawtypes" })
			List<Class<? extends BaseMessageHandler.BaseRemoteInterface>> typedInterfaces = (List<Class<? extends BaseMessageHandler.BaseRemoteInterface>>) (List) remoteInterfaces;

			Launcher.Builder<BaseMessageHandler.BaseRemoteInterface> builder = new Launcher.Builder<BaseMessageHandler.BaseRemoteInterface>()
					.setLocalServices(localServices).setClassLoader(OCTService.class.getClassLoader())
					.setRemoteInterfaces(typedInterfaces).setInput(currentProcess.getInputStream())
					.setOutput(currentProcess.getOutputStream()).configureGson(BinaryDataAdapter::registerAll);

			jsonRpc = builder.create();
			jsonRpc.startListening();

		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Failed to start OCT service process", e);
		}
	}

	private void extractExecutable() throws IOException {
		String os = Platform.getOS();
		String arch = Platform.getOSArch();

		String suffix = "";
		String platformKey;
		if (Platform.OS_WIN32.equals(os)) {
			suffix = ".exe";
			platformKey = "win32";
		} else if (Platform.OS_MACOSX.equals(os)) {
			platformKey = Platform.ARCH_AARCH64.equals(arch) ? "darwin-arm64" : "darwin-x64";
		} else {
			platformKey = Platform.ARCH_AARCH64.equals(arch) ? "linux-arm64" : "linux-x64";
		}

		String resourcePath = EXECUTABLE_BASE + "-" + platformKey + suffix;
		InputStream binaryStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		if (binaryStream == null) {
			// Fallback: try without platform suffix (single binary bundled)
			binaryStream = getClass().getClassLoader().getResourceAsStream(EXECUTABLE_BASE + suffix);
		}
		if (binaryStream == null) {
			throw new IOException("OCT service process binary not found in bundle: " + resourcePath);
		}

		Path tempDir = Files.createTempDirectory("oct-service-process-bin");
		Path tempBinaryPath = tempDir.resolve("oct-service-process" + suffix);
		Files.copy(binaryStream, tempBinaryPath, StandardCopyOption.REPLACE_EXISTING);
		tempBinaryPath.toFile().setExecutable(true);
		executablePath = tempBinaryPath;

		// Clean up temp dir on JVM exit
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				Files.deleteIfExists(tempBinaryPath);
				Files.deleteIfExists(tempDir);
			} catch (IOException ignored) {
			}
		}));
	}

	@Override
	public void close() {
		try {
			if (currentProcess != null) {
				currentProcess.destroy();
				currentProcess.waitFor(2, TimeUnit.SECONDS);
				if (currentProcess.isAlive()) {
					currentProcess.destroyForcibly();
				}
				currentProcess = null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		jsonRpc = null;
	}
}
