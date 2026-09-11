---
status: accepted
---

# ADR-0001: Bridge to OCT via the native `oct-service-process` executable, not a Java reimplementation

## Context

`oct-eclipse` needs to join the Open Collaboration Tools protocol: msgpack
wire format, end-to-end encryption, and Yjs CRDT sync, all implemented in
`open-collaboration-tools` (TypeScript). Eclipse plugins are JVM code. Either
the plugin reimplements that protocol/encryption/CRDT stack in Java, or it
reuses the existing TypeScript implementation as a subprocess.

## Options considered

1. **Reimplement in Java** — full control, no native binary to ship, but
   duplicates and must track the encryption layer, msgpack framing, and Yjs
   CRDT semantics from `open-collaboration-tools` indefinitely.
2. **Bridge to the TypeScript client as a native subprocess** — the plugin
   spawns `oct-service-process` (built from
   `open-collaboration-tools/packages/open-collaboration-service-process`) and
   talks to it over stdio JSON-RPC (lsp4j), the same pattern
   `open-collaboration-vscode` uses conceptually (in-process there, since VS
   Code extensions already run on Node).

## Decision

We bridge to the native `oct-service-process` executable
(`org.eclipse.oct.internal.rpc.ServiceProcess`) rather than reimplementing the
protocol in Java. `open-collaboration-tools` remains the single implementation
of the wire protocol, encryption, and Yjs sync; the Eclipse plugin is a thin
JVM-side client over stdio JSON-RPC.

## Consequences

- A protocol change in `open-collaboration-tools` requires rebuilding the
  native executable and re-staging it into `org.eclipse.oct/oct-bin/` — done
  automatically by `mvn clean verify` via `oct.project.path` (see
  [ARCHITECTURE.md](../ARCHITECTURE.md)), but it means this repo cannot build
  standalone without a sibling `open-collaboration-tools` checkout.
- The plugin never touches encryption keys or Yjs documents directly — that
  logic stays in one place, reducing the risk of the Java side accidentally
  breaking the "server never sees plaintext" invariant.
- Debugging spans two runtimes: a protocol-level bug can be in the Java RPC
  binding (`OCTService`, message handlers) or in the native process itself,
  and reproducing it may require both a JDT debugger and Node.js tooling.
- Packaging carries a native binary per platform (`oct-bin/`), increasing the
  update site's size and requiring Node.js 20+ on `PATH` at build time (see
  `README.md`).
