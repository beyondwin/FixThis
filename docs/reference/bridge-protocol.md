# Bridge Protocol

The **bridge** is the wire protocol between the in-app sidekick (debug-only,
runs inside the target Android app process) and the desktop FixThis processes
(`fixthis-cli` and `fixthis-mcp`). Everything else — the MCP server, the
console UI, the persisted JSON — sits on top of it.

This document describes the wire format, the version policy, and the
checklist for changing the protocol.

## Topology

```text
   ┌────────────────────┐         localabstract socket          ┌─────────────────────────┐
   │  fixthis-cli /     │ ◀─────── adb forward ──────────────▶  │  Android debug app      │
   │  fixthis-mcp       │                                       │  ├ AndroidX Startup     │
   │  (desktop, JVM)    │       ┌───────────────────────────┐   │  ├ FixThis sidekick     │
   │                    │       │ JSON-RPC-ish frames over  │   │  └ in-process bridge    │
   │  Console (browser) │       │ length-prefixed binary    │   │     ↳ Compose semantics │
   └────────────────────┘       └───────────────────────────┘   └─────────────────────────┘
```

- The Android app **does not host** an HTTP or MCP server. It only exposes a
  local-abstract socket inside its own process.
- The desktop side talks to that socket via `adb forward
  tcp:<port> localabstract:fixthis_<package>`.
- Every desktop request carries a session **token** read from app-private
  storage via `adb shell run-as <package> cat files/fixthis/session.json`.
  Mismatched / missing tokens are rejected by the bridge.

## Wire format

The bridge speaks a length-prefixed JSON-frame protocol implemented in
`fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt`:

- **Frame header**: 4 bytes, big-endian unsigned `length`.
- **Frame body**: `length` bytes of UTF-8 JSON.
- **Max frame**: 16 MB.

### Request

```json
{
  "id": "client-generated-id",
  "token": "<session token>",
  "method": "captureScreen",
  "params": { ... }
}
```

### Response

Success:

```json
{ "id": "client-generated-id", "ok": true, "result": { ... } }
```

Error:

```json
{ "id": "client-generated-id", "ok": false, "error": { "code": "string", "message": "string" } }
```

`id` is echoed verbatim. `ok` is the success/failure discriminator. `error.code`
is a stable machine-readable string; `error.message` is human-readable.

## Version constant

A single `VERSION` string identifies the protocol surface. **Current value:
`"1.1"`** (defined in
`fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt`).

The same value is **mirrored** in three other places that must stay in sync.
A `BridgeProtocolVersionSyncTest` in `:fixthis-mcp:test` enforces equality
across all four sites at CI time and names each file with its observed value
on failure:

| File | Purpose | Constant |
|------|---------|----------|
| `fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt` | **Authoritative source** — sidekick-emitted version. | `VERSION` |
| `fixthis-mcp/src/main/console/staleness.js` | Console-side minimum acceptable version. | `MinimumSupportedProtocolVersion` |
| `fixthis-cli/.../BridgeClient.kt` | Desktop CLI's expected version (used to refuse incompatible bridges with a clear error). | `BridgeProtocolVersion` |
| `fixthis-mcp/.../console/ServerVersionRoutes.kt` | Server-side reflection of the version, returned to the console for staleness comparison. | `BridgeProtocolVersion` |

## Staleness banner

At runtime, the console fetches the bridge's reported version and compares it
to its own `MinimumSupportedProtocolVersion`. If they disagree, the console
shows a **red staleness banner** explaining what to rebuild / reinstall.

Two common triggers:

1. **Old sample APK on a new console.** The installed debug app's sidekick
   reports an older `bridgeProtocolVersion` than the console expects. Fix:
   `bash scripts/restart-console.sh --with-app` (or manually re-install the
   debug APK).
2. **Old console JAR on a new sample APK.** The console serves an outdated
   `MinimumSupportedProtocolVersion`. Fix: `bash scripts/restart-console.sh`
   to rebuild the JAR.

The console also compares its bundled build epoch against the running server
on load and shows the same banner when *the JAR itself* is older than the
sources, catching the case where you forgot to restart after a Kotlin edit.

## When to bump the version

Bump `VERSION` whenever a wire-visible change would silently misbehave on an
older partner:

- A new `method` is required to be present (vs. additive optional features).
- A field is **renamed** or **removed** in a request or response.
- A field's **semantics** change (e.g., units, enum values, optional →
  required).

You do **not** need to bump for:

- Pure additive fields with safe defaults (older partner ignores them).
- Implementation-internal refactors that don't change the wire shape.
- Logging / error-message text changes.

## Version-bump checklist

Any PR that changes `BridgeStatus`, `BridgeRequest`, `BridgeResponse`, or any
method signature on the bridge:

1. Bump `BridgeProtocol.VERSION` in
   `fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt` (e.g. `"1.1"` →
   `"1.2"`).
2. Bump `MinimumSupportedProtocolVersion` in
   `fixthis-mcp/src/main/console/staleness.js` to the same value.
3. Bump `BridgeProtocolVersion` in
   `fixthis-cli/.../BridgeClient.kt` to the same value.
4. Bump `BridgeProtocolVersion` in
   `fixthis-mcp/.../console/ServerVersionRoutes.kt` to the same value.
5. Re-bundle console JS:

   ```bash
   node scripts/build-console-assets.mjs
   ```

6. Add a `### Bridge Protocol` entry to `CHANGELOG.md` describing the
   wire-visible change and the new version.
7. Verify staleness banner behavior locally by running an old APK against the
   new console (manual smoke).

CI enforces mirror equality automatically via `BridgeProtocolVersionSyncTest`
(`:fixthis-mcp:test`). If you bump one site and forget another, the test
fails and lists each file with its current value, so you don't have to wait
for a smoke test to catch the drift.

## Token security

The session token is short-lived and never leaves the local machine:

- Generated by the sidekick on app start, written to app-private storage.
- Read by the desktop side via `adb shell run-as <package>` (Android debug
  signing required).
- Included in every bridge request; the sidekick rejects mismatches.

This is **not** a remote-service auth boundary. The bridge is intended for a
trusted local development machine with ADB access to a debug app — see
[Security model](../../SECURITY.md) and [Privacy](privacy.md).

## See also

- [Architecture overview](../architecture/overview.md)
- [ADR-0007 — Feedback console connection recovery](../architecture/adr/0007-feedback-console-connection-recovery.md)
- [Output schema](output-schema.md) — what the bridge ultimately delivers into persisted feedback
