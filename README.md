# Eclipse OCT

An Eclipse plugin for [Open Collaboration Tools](https://github.com/eclipse-oct/open-collaboration-tools)
(OCT) — real-time collaborative editing for your Eclipse workspace, interoperable
with the VS Code extension and any other OCT client. Host a session from Eclipse
and let peers join from VS Code (or vice versa), or run an all-Eclipse session.

The actual collaboration protocol, relay server, and VS Code client live in the
[open-collaboration-tools](https://github.com/eclipse-oct/open-collaboration-tools)
repository; this repo only adds the Eclipse side.

## Installing the plugin

This project doesn't have a public p2 update site or Eclipse Marketplace listing
yet, so for now it's installed from a CI build artifact:

1. Open the [Actions tab](https://github.com/eclipse-oct/oct-eclipse/actions/workflows/build.yml)
   and pick the latest successful run on `main` (green check).
2. Download the `oct-p2-repository` artifact and unzip it. Inside you'll find a
   single zip, e.g. `org.eclipse.oct.repository-1.0.0-<qualifier>.zip` — this
   is the p2 update site archive itself; **don't unzip this inner one**.
3. In Eclipse: **Help → Install New Software… → Add… → Archive…**, and point
   at that inner zip file directly.
4. Select **Open Collaboration Tools** from the category list, finish the
   wizard, and restart Eclipse when prompted.

Supported platforms are **Linux x86_64, Windows x86_64 and macOS arm64**. The
plugin drives a native helper process that ships per platform, and p2 installs
only the one matching your machine. macOS on Intel is not currently built.

> GitHub Actions artifacts expire after a while (currently 90 days by
> default), so if the latest run's artifact is gone, just re-run the workflow
> via `workflow_dispatch` or wait for the next push to `main`.

## For developers

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| Node.js | 20+ (for building the service-process executable) |

You also need a sibling checkout of
[open-collaboration-tools](https://github.com/eclipse-oct/open-collaboration-tools)
next to this repo (i.e. `../open-collaboration-tools`) — the build compiles its
native executable and the integration tests spin up its server. Override the
location with `-Doct.project.path=/absolute/path/to/open-collaboration-tools`
if it lives elsewhere.

### Project structure

```
oct-eclipse/
  pom.xml                           Parent POM (Tycho)
  org.eclipse.oct/                  Main plugin bundle
    src/                            Java sources
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
    lib/              msgpack jars — downloaded by the build, don't hand-edit
    META-INF/MANIFEST.MF
    plugin.xml
    .launch/          Eclipse launch configs for manual testing (see below)
  org.eclipse.oct.binary.linux.x86_64/     One fragment per platform, each carrying only
  org.eclipse.oct.binary.win32.x86_64/     that platform's oct-bin/oct-service-process-*
  org.eclipse.oct.binary.macosx.aarch64/   (staged by the build, don't hand-edit)
  org.eclipse.oct.tests/            Integration test fragment (Tycho eclipse-test-plugin)
  org.eclipse.oct.feature/          Eclipse feature
  org.eclipse.oct.repository/       p2 update site (category.xml)
  org.eclipse.oct.target/           Target platform (Eclipse 2026-03)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the host/guest role split
and the invariants behind the package layout.

### Building and running from Eclipse (PDE)

1. Open Eclipse with PDE installed.
2. Import `org.eclipse.oct` (and `org.eclipse.oct.tests` if you want to run
   tests from the IDE) as existing Eclipse projects
   (**File → Import → Existing Projects**).
3. Set the active target platform to
   `org.eclipse.oct.target/org.eclipse.oct.target`
   (**Preferences → Plug-in Development → Target Platform**).
4. The project should build cleanly.

Also import the `org.eclipse.oct.binary.*` project for your platform — that's
where the native `oct-service-process` lives, and without it in the workspace a
session fails to start.

`lib/` and the fragment's `oct-bin/` are both filled by `mvn clean verify` (see
below) from the sibling `open-collaboration-tools` checkout, so build headlessly
at least once before running from PDE. A local build only ever produces the
binary for the machine it runs on; the other two fragments stay empty, which is
expected. To produce it by hand instead:

```bash
cd /path/to/open-collaboration-tools
npm install && npm run build
npm run create:executable --workspace=open-collaboration-service-process
# then rename it to the platform-suffixed name ServiceProcess looks for and
# drop it into the matching fragment, e.g. on macOS arm64:
mv packages/open-collaboration-service-process/bin/oct-service-process \
   /path/to/oct-eclipse/org.eclipse.oct.binary.macosx.aarch64/oct-bin/oct-service-process-darwin-arm64
```

#### Trying out a live session (`.launch` configs)

[`org.eclipse.oct/.launch`](org.eclipse.oct/.launch) ships three
**Run → Run Configurations…** entries for exercising a real multi-party
session without leaving the IDE, each launching a separate runtime Eclipse
instance (and workspace) side by side:

- **Eclipse Application** — the host instance.
- **Eclipse Application Guest** / **Eclipse Application Guest2** — two
  further instances to join the host's session as guests.

Run "Eclipse Application" first, host a session from it, then run one or both
guest configs and join using the session code — useful for manually verifying
editor sync, cursors/selections, and file-system operations across peers.

### Headless Tycho build

```bash
# From oct-eclipse/. Uses oct.project.path=../open-collaboration-tools by default.
mvn clean verify

# Override the path to open-collaboration-tools if it lives elsewhere:
mvn clean verify -Doct.project.path=/absolute/path/to/open-collaboration-tools
```

The build runs `npm install`, `npm run build` and `npm run create:executable`
inside the referenced open-collaboration-tools checkout, then stages the
produced binary into the binary fragment for this platform. **This means the
build hard-fails if Node.js 20+ is not on `PATH` or the sibling checkout is
missing.**

A local build is therefore single-platform. To assemble an update site that
serves all three, hand Tycho a directory of pre-built, platform-suffixed
binaries instead — which is exactly what CI's per-platform matrix does:

```bash
mvn clean verify -Doct.binaries.dir=/absolute/path/to/binaries
```

With that set, the npm `create:executable` step is skipped and each fragment
picks its own binary out of that directory by name
(`oct-service-process-linux-x64`, `-win32.exe`, `-darwin-arm64`).

The p2 update site is produced at `org.eclipse.oct.repository/target/*.zip`
— this is the same artifact CI uploads and that the installation instructions
above point at.

#### Running this from Eclipse (`oct-eclipse.launch`)

[`.launch/oct-eclipse.launch`](.launch/oct-eclipse.launch) runs the same
`mvn clean verify` via m2e's **embedded** Maven runtime, inside Eclipse's own
JVM. Like the `OCT Tests.launch` case below, that JVM does not inherit the
`PATH` your shell builds up from `.zshrc`/`.zprofile` (nvm, volta, fnm,
Homebrew, ...) when Eclipse itself was started from the Dock/Finder/Spotlight
— so the antrun step that shells out to `npm install` /
`npm run create:executable` can fail with `npm` not found, even though `npm`
works fine from a terminal. Make sure `npm` is resolvable from wherever
Eclipse's process inherited its `PATH`, or — more reliably — just run
`mvn clean verify` from a terminal instead of using this launch config.

One-time setup before running single-module commands below:

```bash
mvn install -DskipTests
```

### Running the integration tests

The [`org.eclipse.oct.tests`](org.eclipse.oct.tests) fragment contains JUnit
integration tests that spin up the real Node.js OCT server plus the native
`oct-service-process` executable and drive the plugin's real internal classes
to verify the host/guest handshake and file-system path conversion against the
open-collaboration-tools protocol.

```bash
# Run the entire suite (built and executed as part of `mvn verify`):
mvn clean verify

# Or, once `mvn install -DskipTests` has run at least once, a single class:
mvn -pl org.eclipse.oct.tests -am -Dtycho.mode=maven integration-test -Dtest=<ClassName>
```

Prerequisites for the tests: Node.js 20+ available on `PATH`, a checkout of
open-collaboration-tools reachable via `-Doct.project.path=...` (defaults to
`../open-collaboration-tools`).

Timing varies a lot by what a test class spins up: a pure unit-style class
(e.g. `OctPathsTest`) runs in ~12s, but most integration classes launch a real
`oct-service-process` and/or the PDE UI harness and take 30-55s
(`PathConversionTest` ~30s, `HandshakeTest` ~45s, `EditorAdoptionTest` ~52s) —
plan for the latter, not the former, when picking a timeout.

#### Running from Eclipse (`OCT Tests.launch`)

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

### Continuous integration

CI runs the very same `mvn clean verify` on every push to `main` and every
pull request — see [.github/workflows/build.yml](.github/workflows/build.yml).
It checks `open-collaboration-tools` out as a sibling and tracks that
project's `main`, so an upstream commit can occasionally turn a PR red with
nothing changed here.
