# Eclipse OCT — Build Instructions

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| Node.js | 20+ (for building the service-process executable) |

## Quick start (PDE / Eclipse IDE)

1. Open Eclipse with PDE installed.
2. Import `org.eclipse.oct` as an existing Eclipse project (**File → Import → Existing Projects**).
3. Set the active target platform to `org.eclipse.oct.target/org.eclipse.oct.target`
   (**Preferences → Plug-in Development → Target Platform**).
4. The project should build cleanly. Run with **Run → Run As → Eclipse Application**.

> **Note:** The `lib/` jars are pre-downloaded. The `oct-bin/` executable is built automatically
> by `mvn verify` (see below) from the sibling `open-collaboration-tools` checkout.

## Building the service-process executable manually

Normally the Tycho build does this for you. To produce the binary by hand:

```bash
cd /path/to/open-collaboration-tools
npm install
npm run create:executable   # produces binaries under packages/open-collaboration-service-process/bin/
```

Then copy the resulting binary into `org.eclipse.oct/oct-bin/`:

```bash
cp packages/open-collaboration-service-process/bin/oct-service-process* org.eclipse.oct/oct-bin/
```

## Headless Tycho build

```bash
# From oct-eclipse/. Uses oct.project.path=../open-collaboration-tools by default.
mvn clean verify

# Override the path to open-collaboration-tools if it lives elsewhere:
mvn clean verify -Doct.project.path=/absolute/path/to/open-collaboration-tools
```

The build runs `npm install` and `npm run create:executable` inside the referenced
open-collaboration-tools checkout, then stages the produced binary into
`org.eclipse.oct/oct-bin/`. **This means the build hard-fails if Node.js 20+ is not
on `PATH` or the sibling checkout is missing.**

The p2 update site is produced at `org.eclipse.oct.repository/target/repository/`.

## Running the integration tests

The [`org.eclipse.oct.tests`](org.eclipse.oct.tests) fragment contains JUnit 5
integration tests that spin up the real Node.js OCT server plus the native
`oct-service-process` executable and drive the plugin's real internal classes
to verify the host/guest handshake and file-system path conversion against the
open-collaboration-tools protocol.

```bash
# Run the entire suite (built and executed as part of `mvn verify`):
mvn -pl org.eclipse.oct.tests -am verify

# Or, when you already have the target platform + native binary built,
# just re-run the tests:
mvn -pl org.eclipse.oct.tests -am -Dtycho.mode=maven test
```

Prerequisites for the tests: Node.js 20+ available on `PATH`, a checkout of
open-collaboration-tools reachable via `-Doct.project.path=...` (defaults to
`../open-collaboration-tools`).

### Running from Eclipse (`OCT Tests.launch`)

When Eclipse itself is launched from the Dock/Finder/Spotlight rather than a
terminal, the JDT JUnit launcher's JVM does **not** inherit the `PATH` that a
shell builds up from `.zshrc`/`.zprofile` (nvm, volta, fnm, Homebrew, ...), so
a naive `node ...` process launch fails with
`Cannot run program "node": error=2, No such file or directory` even though
`node` works fine from a terminal.

`OctTestServer` works around this automatically by (in order): honoring a
`-Doct.node.executable=/path/to/node` VM argument if set, checking well-known
install locations (Homebrew, system packages, Volta, nvm), and finally asking
your login shell (`$SHELL -lc "command -v node"`) to resolve it. If all of
that still fails on your machine, find the path with `command -v node` in a
terminal and add it to the `VM_ARGUMENTS` in `OCT Tests.launch` (or the
launch config's *Arguments* tab), e.g.:

```
-Doct.node.executable=/opt/homebrew/bin/node
```

## Installing into Eclipse

Add the update site URL or local path via **Help → Install New Software**.

## Project structure

```
oct-eclipse/
  pom.xml                           Parent POM (Tycho)
  org.eclipse.oct/                  Main plugin bundle
    src/                            Java sources (M1–M4)
      org/eclipse/oct/
        protocol/     POJOs (Workspace, SessionData, Peer, FileContent, …)
        editor/       EditorManager, DocumentSyncListener, PeerAnnotation, drawing strategies
        ui/           SessionView, StatusBarContribution, command handlers
        prefs/        OCTSettings, OCTPreferencePage
        util/         EventEmitter, OctPaths
        internal/
          rpc/        ServiceProcess, adapters, OCTService, FileSystemService, handlers
          fs/         OctFileSystem (EFS guest), WorkspaceFileSystemService (host)
          auth/       AuthenticationService + secure storage
    lib/              Third-party jars (lsp4j, jackson, msgpack)
    bin/              oct-service-process executables (per platform)
    META-INF/MANIFEST.MF
    plugin.xml
  org.eclipse.oct.tests/            Integration test fragment (Tycho eclipse-test-plugin)
  org.eclipse.oct.feature/          Eclipse feature
  org.eclipse.oct.repository/       p2 update site (category.xml)
  org.eclipse.oct.target/           Target platform (.target — Eclipse 2025-03)
```

## Milestone status

| # | Status | Description |
|---|--------|-------------|
| M1 | ✅ | Transport skeleton: MANIFEST, lib jars, ServiceProcess, Launcher, BinaryDataAdapter, MessageTypeAdapter, protocol POJOs, OCTService/FileSystemService interfaces |
| M2 | ✅ | Host session: SessionService, CollaborationInstance, OCTMessageHandler, FileSystemMessageHandler, WorkspaceFileSystemService, WorkspaceChangeListener, commands (Host/Join/Close/Logout), SessionView, StatusBar, OCTSettings, Activator |
| M3 | ✅ | Guest EFS: OctFileSystem (scheme `oct`), OctFileStore (stat/readDir/read/write/mkdir/delete/move via RPC), linked IFolder on join, change-event cache invalidation |
| M4 | ✅ | Editor collaboration: IPartListener2 editor tracking, DocumentSyncListener (echo-suppressed), SelectionSyncListener, remote apply with DocumentRewriteSession, PeerAnnotation + PeerCursorDrawingStrategy + PeerSelectionDrawingStrategy (AnnotationPainter), follow-mode |
| M5 | 🔲 | Auth UI polish: provider chooser dialog, form auth fields, SWT Browser login flow |
| M6 | ✅ | Tycho packaging: feature, p2 repository, .target (Eclipse 2025-03 + Orbit), executable staging via maven-antrun |