# Eclipse OCT

Eclipse plugin for Open Collaboration Tools (OCT): live-shares an Eclipse
workspace with peers via a native `oct-service-process` subprocess bridging to
the `open-collaboration-tools` TypeScript protocol implementation. Java 21,
Maven 3.9+/Tycho 5.0.4, Eclipse 2026-03 target platform.

**Requires a sibling checkout of `open-collaboration-tools`** (defaults to
`../open-collaboration-tools`, override with `-Doct.project.path=...`) and
Node.js 20+ on `PATH` — the build compiles and stages its native executable.

## Commands

Run from the repo root.

```sh
mvn install -DskipTests               # one-time reactor install, ~30s — needed before any single-module command below
mvn clean verify                      # full build + native executable + all tests, ~3m15s (tests dominate, ~2m45s)
mvn -pl org.eclipse.oct.tests -am -Dtycho.mode=maven integration-test -Dtest=<ClassName>   # single test class, once installed
```

- There is no separate lint/typecheck command — `mvn clean verify` (Tycho
  compiler + PDE manifest checks) is the full verification, and it currently
  passes cleanly on a fresh checkout (verified 2026-09-25, 103/103 tests).
- The single-test command's timing depends heavily on what the class spins up:
  a pure unit-style class (e.g. `OctPathsTest`) runs in ~12s, but most
  integration classes launch a real `oct-service-process` and/or the PDE UI
  harness and take 30-55s (`PathConversionTest` ~30s, `HandshakeTest` ~45s,
  `EditorAdoptionTest` ~52s — timed 2026-09-25) — plan for the latter, not the
  former, when picking a timeout.
- The single-test form runs the `integration-test` phase, not `test`: since
  Tycho 5.0 the `tycho-surefire-plugin:test` goal (it launches an OSGi runtime
  to run tests, so Tycho classifies it as an integration test) is bound to
  `integration-test` rather than `test` — `-Dtycho.mode=maven test` silently
  runs zero tests under this Tycho version.
- The `-Dtycho.mode=maven` single-test form needs `org.eclipse.oct` already
  built and installed to the local `.m2` repo (via `mvn install -DskipTests`
  or a prior full build) — it fails with "Missing requirement: osgi.bundle;
  org.eclipse.oct" otherwise, since Tycho resolves module deps from the
  reactor/local repo, not the workspace.
- Eclipse 2026-03 mirrors a composite p2 repo carrying both a legacy JUnit 5
  bundle set (`junit-jupiter-*` 5.14.3 / `junit-platform-*` 1.14.3) and the
  current JUnit 6 set (everything at 6.0.3) side by side. `org.eclipse.oct.target`
  pins the JUnit units to the 6.0.3 set explicitly (a `version="0.0.0"` range
  lets p2 mix engine/launcher versions across the two sets depending on mirror
  sync timing, producing an `AbstractMethodError` from `ExtensionContext`).
  `org.eclipse.oct.tests`'s `MANIFEST.MF` `Require-Bundle` range and its
  `tycho-surefire-plugin` `providerHint` (`junit6`) must stay in lockstep with
  that pin — Tycho's JUnit provider fragments are version-specific
  (`org.eclipse.tycho.surefire.junit5` only imports `org.junit.jupiter.api`
  `[5,6)`, `.junit6` imports `[6,7)`), so a `providerHint` mismatched to the
  target's actual Jupiter major version reproduces the same error.
- CI runs the very same `mvn clean verify` — see
  [.github/workflows/build.yml](.github/workflows/build.yml), on every push to
  `main` and every pull request. It runs in two stages: a `binaries` matrix
  (ubuntu/windows/macos) that builds one `oct-service-process` per platform and
  uploads it, then `build` on `ubuntu-latest`, which downloads all three and
  passes them to Tycho as `-Doct.binaries.dir`. That split exists because a
  Node single-executable bundle can only be built on the platform it targets,
  and an update site built on one runner alone would ship a binary that is
  unusable everywhere else. It checks this repo and `open-collaboration-tools`
  out as siblings and tracks that project's `main`, so an upstream commit can
  turn a PR red with nothing changed here; pin the `ref:` in the workflow if
  that ever gets in the way. The suite needs a display there
  (`useUIHarness=true`), hence `xvfb-run`. The p2 update site is kept as a
  build artifact; test reports and the workbench `.log` are uploaded on
  failure. Pass `-Doct.tests.logLevel=FINE` (or the `debug_logging` dispatch
  input / `OCT_TESTS_DEBUG_LOGGING` repo variable) to surface the Node OCT
  server's own console output.

## Why and where

- `org.eclipse.oct/` — the plugin bundle; all Java sources under `src/`, plus
  `lib/` (msgpack jars, downloaded by the build — never hand-edit).
- `org.eclipse.oct.binary.{linux.x86_64,win32.x86_64,macosx.aarch64}/` — one
  fragment of the plugin bundle per platform, each carrying only that
  platform's `oct-bin/oct-service-process-<platform>` (staged by the build).
  A local build only fills the fragment for the machine it runs on; CI's
  per-platform matrix hands all three to one Tycho run via
  `-Doct.binaries.dir`. macOS x86_64 is deliberately not built.
- `org.eclipse.oct.tests/` — JUnit 5 integration tests (Tycho
  `eclipse-test-plugin`); spin up a real OCT server + native service process
  and drive the plugin's actual internal classes.
- `org.eclipse.oct.feature/`, `org.eclipse.oct.repository/` — Eclipse feature
  and p2 update site packaging.
- `org.eclipse.oct.target/` — the pinned target platform (Eclipse 2026-03).
- See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the package layout
  inside `org.eclipse.oct/src` and the host/guest role split.

## Conventions

- `internal/` (under `org/eclipse/oct/`) holds the RPC transport, EFS, and
  auth implementations — don't add new public API that depends on
  `internal.*` types from outside their own package tree.
- Java sources carry the `Copyright (c) <year> TypeFox GmbH and others.` /
  `SPDX-License-Identifier: EPL-2.0` header — copy it from a neighbouring file.

## Boundaries and definition of done

- Never hand-edit `lib/`, any fragment's `oct-bin/`, or anything under
  `target/` — regenerate with `mvn clean verify`.
- The plugin must not defeat the OCT protocol's end-to-end encryption (e.g. by
  logging decrypted payloads anywhere the relay server could read them) — see
  [ADR-0001](docs/adr/0001-native-service-process-bridge.md).
- Done means `mvn clean verify` passes locally, with output shown.
- Any change under `editor/`, `internal/fs/`, or `internal/rpc/` updates
  [org.eclipse.oct/docs/sync-and-save-lifecycle.md](org.eclipse.oct/docs/sync-and-save-lifecycle.md)
  in the same change — its "Resolved" section also records fixes that touch no
  message at all, such as cross-thread publication and process-lifecycle races.
- If reality contradicts this file or `docs/`, fix the doc in the same change
  — never work around it silently.

## Pointers

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module layering, native
  process boundary, host/guest roles, invariants.
- [docs/adr/](docs/adr/) — do not contradict accepted ADRs: ADR-0001 (native
  service-process bridge instead of a Java protocol reimplementation),
  ADR-0002 (gate outgoing edits on seed confirmation, with a timeout
  fallback).
- [docs/exec-plans/](docs/exec-plans/active/) — multi-session work; move to
  `completed/` when done.
- [org.eclipse.oct/docs/sync-and-save-lifecycle.md](org.eclipse.oct/docs/sync-and-save-lifecycle.md)
  — editor/file sync trigger→RPC→handler tables, save-path behavior, known
  upstream gaps.
- [README.md](README.md) — human-facing PDE import / "Run As Eclipse
  Application" / update-site install instructions.
