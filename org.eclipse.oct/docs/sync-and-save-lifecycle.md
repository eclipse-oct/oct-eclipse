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
| Editor opened, first time only — **including editors already open when the session starts** (`EditorManager`'s constructor sweeps `getEditorReferences()` via `adoptOpenEditors`, since `partOpened` only fires for editors opened afterwards) | `EditorManager.partOpened` / `adoptOpenEditors` → `trackEditor` → `registerEditor` returns `true` once per path | `awareness/openDocument` (full content, exactly once per registration), followed by `awareness/getDocumentContent` polling in `confirmSeedThenEnableUpdates` before `sendUpdates` is flipped on (closes a real corruption race against the seed — see [ADR-0002](../../docs/adr/0002-seed-confirmation-gate-with-timeout-fallback.md)) |
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
| `fileSystem/change` (guest only — host ignores its own broadcast) | `CollaborationInstance.handleFileSystemChange` | Invalidates EFS caches. For `Update` on an open editor, `saveIfOpen` marks it clean and **skips** `refreshLocal` — a refresh would auto-reload the now-clean editor and `DocumentSyncListener` would echo the whole buffer as an insert (duplicated content). Closed files still refresh so the explorer stays current, via `project.refreshLocal(DEPTH_INFINITE)` — a full-project rescan on every change, not just the changed path; functionally safe today but worth revisiting if file churn on large workspaces ever makes it expensive. |
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
