# Backlog

Known minor issues and characteristics that are not actively being worked on
— not bugs severe enough for an exec plan, not architectural enough for an
ADR. Pull an item out into its own exec plan in
[exec-plans/active/](exec-plans/active/) once someone actually starts on it.

- **`EditorManager` hardcodes the outgoing peer id as the literal string
  `"self"`** instead of the plugin's real peer id — `sendCurrentSelection`
  (`EditorManager.java:429`) and the `SelectionSyncListener` it constructs
  (`EditorManager.java:492`) both do this; `SelectionSyncListener` itself just
  forwards whatever `selfPeerId` it's given. Harmless today because
  `open-collaboration-service-process`'s `updateYjsObjectSelection` ignores
  the `peer` field on outgoing selections (it ties the selection to the
  sender's own Yjs awareness state instead) — but sloppy, and would become a
  real bug if that field is ever read on the wire. The plugin does not
  currently track its own real peer id anywhere; wiring one through would be
  a small follow-up to `CollaborationInstance.initPeers`.
- **Socket.IO disconnect-detection latency on an unconfigured OCT server is
  ~30-45s.** When a peer's process is killed abruptly (vs. a clean
  `room/closeSession`), the server only notices via its ping/pong heartbeat —
  measured locally at ~30-35s, consistent with the default `pingInterval`
  (25s) + `pingTimeout` (20s) upper bound of ~45s; other peers' `peerLeft`
  notification is delayed by exactly that long (see
  `HandshakeTest.guestDisconnectNotifiesHost`). Both values are now
  configurable server-side via `OCT_PING_INTERVAL`/`OCT_PING_TIMEOUT`
  ([open-collaboration-tools#220](https://github.com/eclipse-oct/open-collaboration-tools/pull/220),
  not yet merged as of 2026-10-09) — once that lands, the test suite could set
  both low to shorten this wait instead of eating the full default. The
  defaults, and therefore this plugin's own latency against an unconfigured
  server, stay unchanged either way.
