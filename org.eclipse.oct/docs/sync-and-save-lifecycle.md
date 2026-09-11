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
| Editor opened, first time only | `EditorManager.partOpened` → `registerEditor` returns `true` once per path (also checked against `seededPaths`) | `awareness/openDocument` (full content, exactly once), followed by `awareness/getDocumentContent` polling in `confirmSeedThenEnableUpdates` before `sendUpdates` is flipped on (see gap below — closes a real corruption race) |
| Disk change (host only) — save, create, delete, rename | `WorkspaceChangeListener.resourceChanged` (only for hosted projects) | `fileSystem/change` broadcast |
| Guest saves a file | `OctFileStore.openOutputStream().close()` (fired by Eclipse's normal save lifecycle on the linked `oct://` resource) | `fileSystem/writeFile` request → host |

## Incoming (peers → Eclipse)

| Notification | Handler | Effect |
|---|---|---|
| `awareness/updateDocument` | `CollaborationInstance.updateDocument` → `EditorManager.updateDocument` | Applies `IDocument.replace()` with echo suppression — **only if the path has a registered `EditorState`**, i.e. is actually open somewhere locally. If not open, silently dropped (by design — there's no live buffer to mutate; the next open / `getDocumentContent` pulls the current Yjs content anyway). |
| `awareness/updateTextSelection` | `EditorManager.updateTextSelection` | Renders peer cursor/selection annotations, plus per-peer follow-mode navigation if `followingPeerId` is set. |
| `editorOpened` (host only) | `CollaborationInstance.editorOpened` → `EditorManager.guestOpenedEditor` | Seeds Yjs with real content (live doc if already open, else disk read) exactly once per path (`seededPaths` guard); only opens/activates the UI editor if "Follow guest selection" is on. |
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
