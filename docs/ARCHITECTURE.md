# Architecture

How `oct-eclipse` is put together: module layering, the process boundary to
the native OCT service, and the invariants that hold across both.

## Modules (Tycho reactor, see root `pom.xml`)

| Module | Packaging | Purpose |
|---|---|---|
| `org.eclipse.oct.target` | `eclipse-target-definition` | Pins the target platform (Eclipse 2025-03 + Orbit). Every other module resolves against it. |
| `org.eclipse.oct` | `eclipse-plugin` | The plugin itself — all Java sources, the native `oct-bin/` executable, third-party `lib/` jars. |
| `org.eclipse.oct.tests` | `eclipse-test-plugin` | JUnit 5 integration tests, run as an Eclipse test fragment against `org.eclipse.oct`. |
| `org.eclipse.oct.feature` | `eclipse-feature` | Packages `org.eclipse.oct` as an installable Eclipse feature. |
| `org.eclipse.oct.repository` | `eclipse-repository` | Produces the p2 update site from the feature. |

Build order follows this table top to bottom; `org.eclipse.oct.tests` and
`org.eclipse.oct.feature` both depend on `org.eclipse.oct` being built (or, for
a single-module run, `install`ed) first — see the single-test command in
[AGENTS.md](../AGENTS.md).

## Package layout inside `org.eclipse.oct/src`

```
org/eclipse/oct/
  protocol/     POJOs mirroring the OCT wire protocol (Workspace, SessionData, Peer, FileContent, …)
  editor/       EditorManager, DocumentSyncListener, SelectionSyncListener, PeerAnnotation, drawing strategies
  ui/           SessionView, StatusBarContribution, command handlers (org/eclipse/oct/ui/commands)
  prefs/        OCTSettings, OCTPreferencePage, OCTPreferenceInitializer
  util/         EventEmitter, OctPaths (path conversion between Eclipse and OCT protocol shapes)
  internal/
    rpc/        ServiceProcess (native process lifecycle), adapters, OCTService, FileSystemService, message handlers
    fs/         OctFileSystem (EFS guest side), WorkspaceFileSystemService (host side), WorkspaceChangeListener
    auth/       AuthenticationService, secure storage, login dialogs
```

`internal/` holds the three packages whose types must not be referenced from
outside the plugin (RPC transport, EFS implementation, auth). Everything else
is a public API surface other Eclipse plugins could in principle depend on —
keep that in mind before adding new top-level packages.

## The native process boundary

`org.eclipse.oct.internal.rpc.ServiceProcess` launches `oct-bin/oct-service-process`
(built from the sibling `open-collaboration-tools` checkout's
`open-collaboration-service-process` package, see its own `AGENTS.md`) and
talks to it over stdio JSON-RPC (lsp4j). This is a deliberate reuse of the
TypeScript OCT client rather than a from-scratch Java reimplementation of the
protocol, encryption, and Yjs sync layers — see
[ADR-0001](adr/0001-native-service-process-bridge.md).

Consequence: a protocol-shape change in `open-collaboration-tools` requires
rebuilding the native executable and re-staging it into `org.eclipse.oct/oct-bin/`
(`mvn clean verify` does this automatically; see AGENTS.md). The plugin's own
Java POJOs in `protocol/` must be kept in sync with the TypeScript types by
hand — there is no shared schema.

## Session roles: host vs. guest

- **Host**: owns the on-disk workspace. `WorkspaceFileSystemService` reads/writes
  real files directly. `WorkspaceChangeListener` watches the Eclipse resource
  delta and broadcasts `fileSystem/change`.
- **Guest**: sees the shared workspace through `OctFileSystem`, an Eclipse EFS
  (`org.eclipse.core.filesystem`) implementation backed entirely by RPC calls
  to the host (`OctFileStore` — stat/readDir/read/write/mkdir/delete/move).
  Nothing is written to a guest's local disk outside the linked `oct://` project.

Editor and selection sync (`editor/`) work identically for both roles — see
[sync-and-save-lifecycle.md](../org.eclipse.oct/docs/sync-and-save-lifecycle.md)
for the full message-level trigger/handler table and the save-path edge cases.

## Invariants

- **The server never sees plaintext.** This plugin is a client of the OCT
  end-to-end-encrypted relay (see `open-collaboration-tools`'s ADR-0001); do
  not add code here that would defeat that (e.g. logging decrypted payloads to
  a location the server can read).
- **`internal/` stays internal.** Only `ServiceProcess`/`OCTService` (rpc),
  `OctFileSystem`/`WorkspaceFileSystemService` (fs), and `AuthenticationService`
  (auth) implement the actual transport, filesystem, and credential mechanics;
  `editor/`, `ui/`, `prefs/` depend on them but not vice versa.
- **`lib/` and `oct-bin/` are build outputs, not source.** Both are regenerated
  by the Tycho build (`lib/` from `pom.xml`-declared dependencies is currently
  committed as pre-downloaded jars — see `README.md`; `oct-bin/` from the
  sibling checkout). Never hand-edit either.

## Pointers

- [`../AGENTS.md`](../AGENTS.md) — verified commands, conventions, definition of done.
- [`adr/`](adr/) — architectural decisions; do not contradict an accepted one.
- [`exec-plans/active/`](exec-plans/active/) — in-progress multi-session work.
- [`../org.eclipse.oct/docs/sync-and-save-lifecycle.md`](../org.eclipse.oct/docs/sync-and-save-lifecycle.md) — editor/file sync message flow, save-path behavior, known upstream gaps.
- `../README.md` — human-facing build/install instructions (PDE import, running from Eclipse).
