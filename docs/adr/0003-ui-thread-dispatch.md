---
status: accepted
---

# ADR-0003: Route UI dispatch through `UIThread` and publish cross-thread state explicitly

## Context

Four threads touch this plugin's state, and only one of them may touch SWT:

- the **lsp4j JSON-RPC reader thread**, which delivers every inbound
  notification (`peerJoined`, `peerLeft`, `sessionClosed`, document and
  selection updates),
- the **process-exit callback** of `ServiceProcess`, which fires when the
  native executable dies,
- **Eclipse `Job`/`WorkspaceJob` workers**, which run room creation, joining,
  and guest project deletion,
- the **SWT UI thread**, which owns views, editors, dialogs, and annotations.

Four separate bugs turned out to be the same mistake — see the "Resolved"
entries in
[sync-and-save-lifecycle.md](../../org.eclipse.oct/docs/sync-and-save-lifecycle.md)
for the full incident narratives: a `Process` handle published through a plain
field and read twice (NPE when the process exited mid-teardown), `guests` and
`EventEmitter.listeners` kept in plain `ArrayList`s while being mutated from
the reader thread and iterated on the UI thread (NPE from a half-grown array
snapshot, and listeners silently missed for lack of a happens-before edge), and
`Display.getDefault()` on the reader thread during workbench shutdown.

That last one is the trap worth naming: `Display.getDefault()` does **not**
return null when no display exists — it *creates* one, making the calling
thread that display's UI thread, which on macOS is only legal on the main
thread. `sessionClosed` is precisely the notification that arrives while the
workbench tears down, so the real display was already gone and SWT tried to
build a fresh one on the reader thread, throwing `SWTException: Invalid thread
access` plus a Cocoa warning on stderr. The existing defence —
`PlatformUI.isWorkbenchRunning()` — sat *inside* the runnable in
`EditorManager.dispose`, so it never got the chance to run.

## Options considered

1. **Guard every call site with `PlatformUI.isWorkbenchRunning()`.** What the
   code did before. It failed twice over: the guard is easy to place inside the
   runnable (where it is useless, because the dispatch has already happened),
   and the display *lookup* is itself the crash, so a guard after it is too
   late.
2. **One dispatch chokepoint that never creates a display.** A single
   `org.eclipse.oct.util.UIThread` resolves the display via
   `Display.getCurrent()`/`PlatformUI.getWorkbench().getDisplay()`, returns
   null when there is none left, and drops the work instead of crashing.
3. **Marshal all inbound RPC onto the UI thread at the transport edge.** One
   rule, no per-call-site judgment — but it serialises the reader thread behind
   arbitrary UI work, and still needs a live display during shutdown, so it
   does not solve the actual failure.

## Decision

Option 2, in two halves:

- **Dispatch.** Every hop to the UI from a non-UI thread goes through
  `org.eclipse.oct.util.UIThread` (`asyncExec`/`syncExec`/`timerExec`, or
  `display()` when the caller needs the null check itself).
  `Display.getDefault()` is banned in `org.eclipse.oct` — a review-time
  convention, not a mechanically enforced one. Code that already holds a live
  widget may still use that widget's display (`widget.getDisplay().asyncExec(…)`)
  — owning the widget is proof the display is alive.
- **Publication.** State shared across those threads is published explicitly:
  a `volatile` field captured into a local before use (`ServiceProcess`'s
  `currentProcess`/`jsonRpc`/`closing`), or a concurrent collection when the
  readers iterate (`CollaborationInstance.guests`,
  `EventEmitter.listeners` — both `CopyOnWriteArrayList`, whose iteration is an
  immutable snapshot, so neither needs a defensive copy).

## Consequences

- **Dropped work is the normal shutdown path, not an error.** Once the display
  is gone `UIThread` silently discards the runnable, so any handler that owes a
  peer a reply must settle it *outside* the dispatch:
  `OCTMessageHandler.joinSessionRequest` completes its future with `false`
  rather than leaving the guest waiting forever.
- **Shutdown-critical work should avoid the UI thread entirely.**
  `sessionClosed` schedules its guest-project deletion as a `WorkspaceJob`
  straight off the reader thread — hopping onto the UI thread first bought
  nothing and only tied the cleanup to a display that is already gone.
- **The ban is scoped to the plugin bundle.** `org.eclipse.oct.tests` may use
  `Display.getDefault()`: the PDE UI harness guarantees a live display, so it
  returns that one instead of creating a new one.
- **Neither half is enforced mechanically.** Both the `Display.getDefault()`
  ban and the publication discipline remain review-time judgment calls — the
  same gap [ADR-0002](0002-seed-confirmation-gate-with-timeout-fallback.md)
  records for its seed gate. A new field shared between the reader thread and
  the UI, or a new dispatch site reaching for `Display.getDefault()` instead
  of `UIThread`, is the case to look at hardest.
- **`Display.getCurrent()` stays available** for "am I on the UI thread?" tests
  (`OctSessionHandler`), since it returns null rather than creating a display.
