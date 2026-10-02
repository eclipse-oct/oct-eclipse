# Editor & File Sync Lifecycle

How Eclipse propagates editor and file-system changes to/from OCT peers, and
how saving works in both directions. Verified against the `open-collaboration-vscode`
and `open-collaboration-service-process` reference implementations in
`open-collaboration-tools`.

## Outgoing (Eclipse → peers)

| Trigger | Code path | RPC call |
|---|---|---|
| Live keystroke in an open editor | `DocumentSyncListener.documentChanged` (guarded by `sendUpdates`, echo-safe) | `awareness/updateDocument` (incremental) |
| Caret/selection change | `SelectionSyncListener.selectionChanged` | `awareness/updateTextSelection` |
| Editor opened, first time only — **including editors already open when the session starts** (`EditorManager`'s constructor sweeps `getEditorReferences()` via `adoptOpenEditors`, since `partOpened` only fires for editors opened afterwards) | `EditorManager.partOpened` / `adoptOpenEditors` → `trackEditor` → `registerEditor` returns `true` once per path | `awareness/openDocument` (full content, exactly once per registration), followed by `awareness/getDocumentContent` polling in `confirmSeedThenEnableUpdates` before `sendUpdates` is flipped on (see gap below — closes a real corruption race) |
| Disk change (host only) — save, create, delete, rename | `WorkspaceChangeListener.resourceChanged` (only for hosted projects) | `fileSystem/change` broadcast |
| Guest saves a file | `OctFileStore.openOutputStream().close()` (fired by Eclipse's normal save lifecycle on the linked `oct://` resource) | `fileSystem/writeFile` request → host |

## Incoming (peers → Eclipse)

| Notification | Handler | Effect |
|---|---|---|
| `init` (guest) | `OCTMessageHandler.init` → `CollaborationInstance.initPeers` → `autoFollowHost` | First point at which the host's peer id is known, which is what follow mode keys on. Arms `EditorManager.followPeer(host.id)` when the `autoFollowHost` preference is on (the default), so a guest starts out following the host; the actual navigation still waits for the next `awareness/updateTextSelection`, since that is what carries a position to jump to. Guest-side only — on a host the same `followGuestSelection` flag means the opposite thing ("a guest opening a file may steal my editor focus") and deliberately stays off. Armed at most once per session, so a re-delivered `init` cannot switch following back on after the user unchecked it in the Session view. |
| `awareness/updateDocument` | `CollaborationInstance.updateDocument` → `EditorManager.updateDocument` | Applies `IDocument.replace()` with echo suppression — **only if the path has a registered `EditorState`**, i.e. is actually open somewhere locally. If not open, silently dropped (by design — there's no live buffer to mutate; the next open / `getDocumentContent` pulls the current Yjs content anyway). |
| `awareness/updateTextSelection` | `EditorManager.updateTextSelection` | Renders peer cursor/selection annotations, plus per-peer follow-mode navigation if `followingPeerId` is set. Also sweeps the reporting peer's annotations out of every *other* open editor — a peer that switched files never gets an explicit empty update for the file it left (see the `peerLeft` row below), so the old file's `PeerAnnotation`s would otherwise linger forever. |
| `editorOpened` (host only) | `CollaborationInstance.editorOpened` → `EditorManager.guestOpenedEditor` | Host already has the file open: nothing to do — its registered editor seeded the path and holds the authoritative (possibly unsaved) buffer. Host does not: pushes the **on-disk** content via `awareness/openDocument`, exactly once per path (`seededPaths` guard), without opening an editor. Only opens/activates the UI editor if "Follow guest selection" is on. Upstream `editor.onOpen`'s `readOwnFile` is a second backstop, but it also reads disk, never a dirty host buffer. |
| `peerLeft` | `CollaborationInstance.peerLeft` → `EditorManager.forgetPeer` | Removes the peer's entry from `peerDocumentPaths`, resets `followingPeerId`/`followGuestSelection` if the departed peer was the one being followed, releases its `PeerColors` slot, and sweeps its `PeerAnnotation`s from every open editor. Nothing over the wire ever tells a client a peer's selections dropped to zero (`checkSelectionUpdated` in `open-collaboration-service-process` only iterates *present* awareness states), so this cleanup has to be driven locally from `peerLeft` rather than from another awareness update. |
| `fileSystem/change` (guest only — host ignores its own broadcast) | `CollaborationInstance.handleFileSystemChange` | Invalidates EFS caches. For `Update` on an open editor, `saveIfOpen` marks it clean and **skips** `refreshLocal` — a refresh would auto-reload the now-clean editor and `DocumentSyncListener` would echo the whole buffer as an insert (duplicated content). Closed files still refresh so the explorer stays current. |
| `fileSystem/writeFile` (host) | `FileSystemMessageHandler.writeFile` → `EditorManager.saveIfOpen` | If host has it open: replaces content only if it actually differs (avoids a needless echo) and calls `editor.doSave()`. Otherwise falls straight through to `WorkspaceFileSystemService.writeFile` (direct disk write). |
| `fileSystem/writeFile` (guest) | `FileSystemMessageHandler.writeFile` → `EditorManager.saveIfOpen` | Host already persisted the file. Guest applies content if needed, then `saveDocument(..., overwrite=true)` with `OctFileStore.suppressWrite` so the editor goes clean without echoing `writeFile` back or hitting Eclipse's "file changed on the file system" dialog. File not open: cache invalidate + refresh only. |

## Will "save" work?

### Host presses Ctrl+S

Works. It's a pure local Eclipse save (no RPC for the write itself).
`WorkspaceChangeListener` sees the `CONTENT` delta and broadcasts `Update`,
then {@code propagateSaveToGuests} sends {@code fileSystem/writeFile} so
VS Code (and Eclipse) guests can {@code document.save()} / mark the editor
clean. {@code fs.onChange} alone only refreshes the explorer.

On an Eclipse guest the incoming writeFile must not call {@code editor.doSave()}
(overwrite=false): `refreshLocal` has usually already bumped the oct://
modification stamp, and Eclipse then asks whether to overwrite the file on
disk. Guest {@code saveIfOpen} uses {@code saveDocument(..., overwrite=true)}
and suppresses the EFS write so nothing is echoed back to the host.
A guest-originated save is not echoed back to the same guest (see
{@code CollaborationInstance.propagateSaveToGuests}).

### Guest presses Ctrl+S

Works. Eclipse's normal save lifecycle on the linked resource routes
through `OctFileStore`, sending `writeFile` to the host. Host applies it via
`saveIfOpen` (clearing host's own dirty flag if it has the file open) or a
direct disk write otherwise. The guest's own dirty flag clears through
Eclipse's ordinary local save mechanics, independent of the RPC round trip.
The host's resulting disk write also fires `WorkspaceChangeListener` →
`Update` broadcast to *other* guests (and echoes back to the originating
guest too, but that's a no-op since content already matches).

## Still open / things to revisit

- **Inherent to Yjs**, not something `open-collaboration-tools` is expected to
  change: `Y.Text.insert` silently clamps out-of-range offsets instead of
  throwing/rejecting, so any future caller that races the seed (e.g. a
  different client, or a future Eclipse code path that forgets the gating
  described under Resolved below) fails silently instead of loudly. This is a
  direct consequence of the sibling `open-collaboration-tools` checkout's own
  ADR-0003 ("Use Yjs CRDTs for document convergence" — peer-side CRDT
  convergence via Yjs, forced by that project's end-to-end encryption
  decision; not yet on its `main` branch as of 2026-09-11, only on the
  branch that introduced it) — "correct by construction under concurrency"
  there doesn't mean every caller is protected from misusing the API before
  convergence has happened, which is exactly this race.
  `EditorManager`'s seed-confirmation gate (see Resolved) is what actually
  protects this plugin against it — that workaround stays necessary
  regardless of the upstream fix below, since it guards against *any* client
  racing the seed, not just the empty-string case PR #207 fixed.
- `SelectionSyncListener` always sends the peer id as the literal string
  `"self"`. Harmless today because the service process ignores the `peer`
  field on outgoing selections (it's tied to the sender's own Yjs awareness
  state instead), but it's sloppy and worth cleaning up if the field is ever
  read on the wire.
- `handleFileSystemChange` triggers `project.refreshLocal(DEPTH_INFINITE)`
  on *every* file-system update, even for files unrelated to what's open.
  Functionally safe today but a full-project rescan per change could get
  expensive on large workspaces with a lot of file churn.
- **Socket.IO disconnect-detection latency (not a bug, but a real
  characteristic worth knowing about).** When a peer's process is killed
  abruptly (vs. a clean `room/closeSession`), the OCT server only notices via
  its ping/pong heartbeat — measured locally at ~30-35s, consistent with the
  default `pingInterval` (25s) + `pingTimeout` (20s) upper bound of ~45s.
  Other peers' `peerLeft` notification is delayed by exactly that long. See
  `HandshakeTest.guestDisconnectNotifiesHost`.

## Resolved

<!-- Historical record of fixed issues — kept for context (why the gating
below exists, why a given RPC type is shaped the way it is), not because
there's remaining work here. -->

- **(Fixed here)** A service process that died on its own left no trace, and
  the symptom named nothing useful. `ServiceProcess`'s `onExit` handler only
  logged when it could read stderr — inside a `catch (IOException ignored)`,
  which is exactly what a stream already drained by the process reaper throws —
  so an executable that failed to run produced complete silence. The first
  outgoing RPC then surfaced it as `java.io.IOException: Stream closed` thrown
  from `ProcessBuilder$NullOutputStream`, which is the JDK swapping a dead
  process's stdin pipe, several layers below anything naming the process. The
  handler now always logs the exit code (suppressed only for a `close()` we
  initiated, which would otherwise report every normal session teardown as a
  failure) and appends stderr when it can still be read. The trigger was a CI
  artifact carrying the service process for the wrong platform: the build
  staged a single unsuffixed binary and `extractExecutable` fell back to it on
  every platform, so a Linux build installed on macOS started a binary that
  died instantly. The binary now ships per platform in
  `org.eclipse.oct.binary.*` fragments and is looked up by platform suffix
  only, with no fallback.

- **(Fixed here)** Awareness was completely dead for any file the user already
  had open when the session started. `EditorManager` only registered editors
  via `IPartListener2.partOpened`, which never fires for an already-open tab
  (and `partActivated` bails when there is no `EditorState` yet), so such a
  file got no `DocumentSyncListener`, no `SelectionSyncListener`, no peer
  annotation model and no `awareness/openDocument` seed for the whole session:
  peer cursors never appeared, local typing was never broadcast, incoming
  edits were dropped by `updateDocument`, and content only converged via a
  save (which travels the unrelated `fileSystem/writeFile` path — hence the
  "it only syncs when I press Ctrl+S" symptom). `EditorManager`'s constructor
  now sweeps every window/page's `getEditorReferences()` in `adoptOpenEditors`,
  matching VS Code's `workspace.textDocuments.forEach(registerTextDocument)`
  and IntelliJ's replay of `FileEditorManager.allEditors`.
- **(Fixed here)** `guestOpenedEditor` added a path to `seededPaths` without
  ever calling `openDocument` (the seeding branch was dropped in `cddb528`),
  and `partOpened` then treated that entry as "already pushed" and returned
  before seeding — which also bypassed the ADR-0002 gate by calling
  `enableSendUpdates` directly, exactly the regression that ADR's Consequences
  section warns about. Because only `awareness/openDocument` reaches
  `YjsNormalizedTextDocument.attachLocalDocument`, skipping it left the service
  process computing every offset against the wrong base string (the disk text,
  or `''`), so peer edits landed at wrong locations. `guestOpenedEditor` now
  really seeds from disk when the host has no editor open, and `trackEditor`
  always sends `openDocument`. Re-sending is cheap rather than a full
  delete+insert: upstream's `registerYjsObject` returns early when the shared
  text is already non-empty.
- **(Fixed here — see [ADR-0002](../../docs/adr/0002-seed-confirmation-gate-with-timeout-fallback.md)
  for the decision and its trade-offs)** `awareness/openDocument` is fire-and-forget, and a non-host
  peer's own `text` argument is discarded upstream (`CollaborationInstance
  .registerYjsObject` in `open-collaboration-service-process` only applies it
  when `isHost`) — the local replica only gets real content once the seed
  round-trips through the OCT server. Sending a live edit before that lands
  used to race an empty/stale local Yjs replica: Yjs silently clamps
  out-of-range offsets instead of rejecting them, corrupting the edit for
  every peer (this is exactly what made `AwarenessSmokeTest
  .documentUpdatesReachHost` intermittently fail with the wrong
  `startOffset`). `EditorManager` now starts `sendUpdates` disabled per editor
  and only enables it once `confirmSeedThenEnableUpdates` confirms via
  `awareness/getDocumentContent` that this peer's own replica actually holds
  the seeded content (falling back to enabling it anyway after a 10s timeout,
  so a slow/unreachable server can't permanently freeze outgoing sync).
- **(Fixed here)** `ServiceProcess` published its native `Process` handle through
  a plain mutable field written by two threads: `close()` on an Eclipse `Job`
  worker, and the `onExit()` callback that clears the field when the process
  dies. Every use was a separate field read, so a process exiting mid-teardown
  let `close()` pass its `!= null` guard and then throw
  `NullPointerException: Cannot invoke "java.lang.Process.isAlive()"` — observed
  from a failing "Joining OCT room..." job after the server rejected a join. The
  same window sat between `pb.start()` and the lsp4j `Launcher` wiring, which
  read `getInputStream()`/`getOutputStream()` off the field: a process exiting
  before the launcher was wired would NPE there instead, and since an NPE is not
  an `IOException` it would escape the surrounding catch and the constructor. The field is now `volatile` and
  every user captures it into a local first. The `onExit` handler also stopped
  throwing a `RuntimeException` into its own unobserved `CompletableFuture`,
  which had skipped the field clear on the common non-zero-exit path. Same class
  as the `CopyOnWriteArrayList` entry above: anything the JSON-RPC reader or
  process-exit threads share with UI/`Job` threads needs explicit publication.
- **(Fixed here)** A departed or file-switched peer left ghost cursors/selections
  behind. `CollaborationInstance.peerLeft` called `EditorManager.forgetPeer`,
  but that only ever cleared `peerDocumentPaths` — nothing touched the
  per-editor `AnnotationModel`s holding the `PeerAnnotation`s `updateTextSelection`
  had added, and `followingPeerId`/`followGuestSelection` were never reset
  either (leaving a host who had followed a peer that then left in "any guest
  may steal my editor focus" mode for the *next* guest). The same gap caused
  stale annotations on a plain file switch, since (as the `peerLeft` row above
  explains) the service process never sends an empty update for a path whose
  selections just dropped to zero — VS Code avoids this entirely by giving each
  peer ownership of its own `TextEditorDecorationType`s and disposing them on
  `room.onLeave`. `forgetPeer` now sweeps `PeerAnnotation`s from every open
  editor and resets follow state, `updateTextSelection` sweeps a reporting
  peer's annotations out of every editor other than the one it just reported
  in, and `PeerColors.release` returns a departed peer's color to the palette.
  `CollaborationInstance.guests` also moved from a plain `ArrayList` to a
  `CopyOnWriteArrayList`, since `peerJoined`/`peerLeft`/`initPeers` mutate it
  from the JSON-RPC reader thread while `SessionView.participants` iterates it
  on the UI thread. See `PeerAnnotationCleanupTest` and the extended
  `HandshakeTest.guestDisconnectNotifiesHost`.
- **(Fixed here)** Closing the guest Eclipse ended the session with
  `org.eclipse.swt.SWTException: Invalid thread access` out of
  `OCTMessageHandler.sessionClosed`, plus a `***WARNING: Display must be
  created on main thread due to Cocoa restrictions` on stderr. The trap is
  that `Display.getDefault()` does not return null when no display exists —
  it *creates* one, making the calling thread that display's UI thread, which
  on macOS is only legal on the main thread. Every dispatch out of the
  JSON-RPC reader went through it, and `sessionClosed` is precisely the
  notification that arrives while the workbench is tearing down: the real
  display was already gone, so SWT tried to build a fresh one on the reader
  thread and threw. The same window hit `EditorManager.dispose`, whose
  `PlatformUI.isWorkbenchRunning()` guard sat *inside* the runnable and so
  never got the chance to run. All 18 dispatch sites now go through
  `org.eclipse.oct.util.UIThread`, which resolves the display via
  `Display.getCurrent()`/`PlatformUI.getWorkbench().getDisplay()` — never
  creating one — and drops the work when no live display is left.
  `joinSessionRequest` completes its future with `false` rather than dropping
  it, since an uncompleted future would leave the guest waiting for a
  response forever, and `sessionClosed` stopped hopping onto the UI thread
  altogether: the guest project deletion runs in a `WorkspaceJob` anyway, so
  it now survives the shutdown race instead of depending on a display. Same
  class as the `ServiceProcess` and `CopyOnWriteArrayList` entries above:
  anything the JSON-RPC reader shares with the UI also has to cope with the
  UI being gone.
- **(Fixed here)** `EventEmitter` kept its listeners in a plain `ArrayList`
  while every one of its instances is subscribed on one thread and fired on
  another: `onPeersChanged`/`onPresenceChanged` fire from the JSON-RPC reader
  and `onSessionCreated`/`onSessionClosed` from an Eclipse `Job` worker, all
  subscribed by views and command handlers on the UI thread, and
  `onAuthAborted` is the mirror image — subscribed from the room-creation
  `Job`, fired from a dialog on the UI thread. The defensive copy in `fire`
  only looked safe: `new ArrayList<>(listeners)` ends up in
  `Arrays.copyOf(elementData, size)`, two separate unsynchronized field
  reads, so an `add` that grew the backing array between them produced a
  snapshot padded with trailing `null`s and an NPE on the reader thread,
  while a concurrent `remove` could shift an element into view twice and
  invoke a listener twice. The quieter failure was plain visibility: with no
  happens-before edge, a listener registered on the UI thread could be missed
  entirely, leaving a Session view opened mid-join stale until the next
  unrelated event. Now a `CopyOnWriteArrayList`, which makes iteration an
  immutable snapshot (so `fire` no longer copies at all, and self-unsubscribe
  from inside a callback stays safe). Same class as the
  `CollaborationInstance.guests` entry above.
- **(Fixed upstream, [open-collaboration-tools#207](https://github.com/eclipse-oct/open-collaboration-tools/commit/45c3beea3d2fc3390d50f94abbe38698aa355cca), 2026-08-24 — confirmed present 2026-09-11)**
  The discarded-`text`-on-guest behavior above used to mean a guest could seed
  the *host* with an empty string if the host hadn't opened that path yet.
  `CollaborationInstance.onOpen` in `open-collaboration-service-process` now
  calls the new `readOwnFile()` when `isHost`, seeding from the host's real
  on-disk content instead of `''`. Requires rebuilding
  `open-collaboration-service-process` (and any packages that bundle it) for
  the fix to take effect.
- **(Fixed upstream, [open-collaboration-tools#207](https://github.com/eclipse-oct/open-collaboration-tools/commit/45c3beea3d2fc3390d50f94abbe38698aa355cca), 2026-08-24 — confirmed present 2026-09-11)**
  `room/closeSession` request/params arity mismatch. Calling the Java
  `OCTService.closeSession()` binding (a zero-arg `@JsonRequest`) failed with
  `"Request room/closeSession defines 1 params but received none"`. lsp4j
  sends no params for a zero-arg method, but the TypeScript side's
  `CloseSessionRequest` was declared as `RequestType<void, void, void>` (one
  parameter slot, just typed `void`, which vscode-jsonrpc still counts as
  `numberOfParams = 1`) rather than `RequestType0<...>` (genuinely zero
  params). This silently broke `SessionService.closeCurrentSession()`/the
  guest-side "leave session" and "Sign Out" flows: the RPC failed,
  `leaveRoom()` never ran, and the host only noticed the guest was gone after
  the ~30-45s heartbeat timeout (see "Still open" above) instead of
  immediately — surfacing as "I left the session but I'm still listed as a
  guest on the host". Fixed in `open-collaboration-service-process/src/messages.ts`
  by switching `CloseSessionRequest` to `RequestType0`. Requires rebuilding
  `open-collaboration-service-process` (and any packages that bundle it) for
  the fix to take effect.
