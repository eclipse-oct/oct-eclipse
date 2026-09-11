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
mvn install -DskipTests               # one-time reactor install, ~25s fresh — needed before any single-module command below
mvn clean verify                      # full build + native executable + all tests, ~50s
mvn -pl org.eclipse.oct.tests -am -Dtycho.mode=maven integration-test -Dtest=<ClassName>   # single test class, ~9s once installed
```

- There is no separate lint/typecheck command — `mvn clean verify` (Tycho
  compiler + PDE manifest checks) is the full verification, and it currently
  passes cleanly on a fresh checkout (verified 2026-09-11, 87/87 tests).
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

## Why and where

- `org.eclipse.oct/` — the plugin bundle; all Java sources under `src/`, plus
  `lib/` (pre-downloaded third-party jars) and `oct-bin/` (native executable,
  staged by the build — never hand-edit either).
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

- Never hand-edit `lib/`, `oct-bin/`, or anything under `target/` —
  regenerate with `mvn clean verify`.
- The plugin must not defeat the OCT protocol's end-to-end encryption (e.g. by
  logging decrypted payloads anywhere the relay server could read them) — see
  [ADR-0001](docs/adr/0001-native-service-process-bridge.md).
- Done means `mvn clean verify` passes locally (or, given the currently-known
  failure above, that you've confirmed your change didn't introduce *new*
  failures beyond that baseline), with output shown.
- A change to the sync/save message flow (`editor/`, `internal/fs/`,
  `internal/rpc/`) updates
  [org.eclipse.oct/docs/sync-and-save-lifecycle.md](org.eclipse.oct/docs/sync-and-save-lifecycle.md)
  in the same change.
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
