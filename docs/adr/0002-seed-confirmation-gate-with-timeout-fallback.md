---
status: accepted
---

# ADR-0002: Gate outgoing edits on seed confirmation, with a timeout fallback

## Context

When an editor is opened for the first time, its content must round-trip
through the OCT server as a Yjs seed before this peer's local Yjs replica
holds the real content (`awareness/openDocument` is fire-and-forget; the seed
itself arrives asynchronously via CRDT sync, not the notification). If an
outgoing edit (`awareness/updateDocument`) is sent before that seed lands,
`Y.Text.insert(index, ...)` does not throw on an out-of-range `index` — it
silently clamps to the current (still-empty) end, corrupting the edit for
every peer. This is exactly what made `AwarenessSmokeTest
.documentUpdatesReachHost` intermittently fail with the wrong `startOffset`
(see [sync-and-save-lifecycle.md](../../org.eclipse.oct/docs/sync-and-save-lifecycle.md)).
The clamping behavior is inherent to Yjs, not something this plugin or
`open-collaboration-tools` controls — the sibling `open-collaboration-tools`
checkout's own ADR-0003 ("Use Yjs CRDTs for document convergence") documents
why Yjs is in the stack at all (peer-side CRDT convergence, forced by its
end-to-end-encryption decision) but not this specific misuse-before-convergence
race, so the plugin has to guard against it locally.

## Options considered

1. **Block outgoing edits indefinitely until the seed is confirmed** — safest
   against corruption, but a slow or unreachable server permanently freezes
   editing in that file with no way out for the user.
2. **Gate outgoing edits until seed confirmation, with a bounded timeout
   fallback that enables sync anyway** — `EditorManager.confirmSeedThenEnableUpdates`
   polls `awareness/getDocumentContent` to confirm this peer's replica holds
   the seeded content, and enables `sendUpdates` either on that confirmation
   or after a 10s timeout, whichever comes first.
3. **Don't gate at all; accept the occasional corrupted offset** — simplest,
   but ships a real data-corruption bug to users typing immediately after
   opening a shared file.

## Decision

We gate outgoing edits per editor behind `EditorManager.confirmSeedThenEnableUpdates`
(option 2): `sendUpdates` starts disabled for a freshly opened editor and is
only enabled once the seed is confirmed via `awareness/getDocumentContent`, or
after a 10-second timeout as a fallback so a slow/unreachable server can't
permanently freeze outgoing sync for that file. Edits typed locally while
gated are queued (not dropped) in `DocumentSyncListener.pending` and flushed
atomically once the gate opens.

## Consequences

- Typing into a just-opened shared file within roughly the first
  seed-round-trip window is queued rather than lost or corrupted — but it is
  delayed, not instant, which is a deliberate trade-off against corruption.
- The 10s timeout is a heuristic, not a guarantee: on a very slow or partially
  unreachable server, an edit made after the timeout fires but before the
  real seed lands can still race the underlying Yjs clamp bug. This is
  considered acceptable because it narrows an unbounded corruption window to
  a rare edge case, not because it closes the gap entirely.
- Any future editor-integration code path (a new client, or a change to
  `EditorManager`) that skips this gate reintroduces the original silent
  corruption — there is no lint or test that enforces the gate is present on
  every code path that opens an editor and enables sync; a reviewer has to
  know this ADR exists.
- If `open-collaboration-tools`/Yjs ever makes `Y.Text.insert` reject
  out-of-range offsets instead of clamping, this gate becomes a
  defense-in-depth measure rather than the only protection — worth
  revisiting this ADR if that upstream behavior changes.
