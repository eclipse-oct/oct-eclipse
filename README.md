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

> **Note:** The `lib/` jars are pre-downloaded. The `bin/` executables must be built separately (see below).

## Building the service-process executable

```bash
cd /path/to/open-collaboration-tools
npm install
npm run create:executable   # produces binaries under packages/open-collaboration-service-process/bin/
```

Then copy them into `org.eclipse.oct/bin/`:

```bash
cp packages/open-collaboration-service-process/bin/* org.eclipse.oct/bin/
```

Or pass the path to Maven (see below).

## Headless Tycho build

```bash
# From oct-eclipse/
mvn clean verify

# Auto-stage executables from a sibling open-collaboration-tools checkout:
mvn clean verify -Doct.project.path=../open-collaboration-tools
```

The p2 update site is produced at `org.eclipse.oct.repository/target/repository/`.

## Installing into Eclipse

Add the update site URL or local path via **Help → Install New Software**.

## Project structure

```
oct-eclipse/
  pom.xml                           Parent POM (Tycho)
  org.eclipse.oct/                  Main plugin bundle
    src/                            Java sources (M1–M4)
      org/eclipse/oct/internal/
        protocol/     POJOs (Workspace, SessionData, Peer, FileContent, …)
        rpc/          ServiceProcess, adapters, OCTService, FileSystemService, handlers
        fs/           OctFileSystem (EFS guest), WorkspaceFileSystemService (host)
        editor/       EditorManager, DocumentSyncListener, PeerAnnotation, drawing strategies
        auth/         AuthenticationService + secure storage
        ui/           SessionView, StatusBarContribution, command handlers
        prefs/        OCTSettings, OCTPreferencePage
    lib/              Third-party jars (lsp4j, jackson, msgpack)
    bin/              oct-service-process executables (per platform)
    META-INF/MANIFEST.MF
    plugin.xml
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