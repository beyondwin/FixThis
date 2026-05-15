# FixThis Threat Model

FixThis is a **debug-only** developer tool. It runs on the developer's own
machine and on a debuggable build of their app, connected over ADB. This
document records what we assume about that environment, what we are trying to
protect, and what we are explicitly **not** defending against.

For the reporting flow for newly discovered weaknesses, see `SECURITY.md` at
the repository root.

## Assets

The things worth protecting:

- **Screenshots** captured from the debug app — pixel data of whatever the
  developer was looking at when they hit *Annotate*. May contain sensitive UI
  state even after Compose-level text redaction.
- **Semantics trees** — the structured Compose hierarchy snapshot used to
  resolve targets. Contains composable names, bounds, text, content
  descriptions, and test tags.
- **Source-candidate paths** — best-effort file paths under the developer's
  source tree, sent back with each annotation so an agent can edit the right
  call site.
- **MCP feedback queue** — local session artifacts under `.fixthis/` (and the
  in-memory queue served to MCP clients) that aggregate the three items above
  per handoff batch.

## Trust boundaries

```
+---------------------------+     (1)      +---------------------------+
|  debug app process        | <----------> |  adbd / ADB transport     |
|  (sidekick LocalSocket    |   USB / TCP  |  (developer's own device  |
|   bridge inside the APK)  |              |   or emulator)            |
+---------------------------+              +---------------------------+
                                                       |
                                                       | (2)  adb forward
                                                       v
                                          +---------------------------+
                                          |  host MCP process         |
                                          |  (fixthis-mcp on the      |
                                          |   developer's machine)    |
                                          +---------------------------+
                                                       |
                                                       | (3)  127.0.0.1
                                                       v
                                          +---------------------------+
                                          |  local browser console    |
                                          |  (developer's browser     |
                                          |   on the same host)       |
                                          +---------------------------+
```

1. **Debug app process ↔ ADB bridge.** The sidekick exposes an
   abstract-namespace `LocalSocket` inside the app sandbox; only ADB (via
   `run-as`) and the app itself can reach it. The shared session token is read
   through `adb run-as` so other apps cannot impersonate the host.
2. **ADB bridge ↔ host MCP process.** ADB is treated as an authenticated local
   channel: the developer has authorized this host to talk to this device.
3. **Host MCP process ↔ local browser console.** Loopback HTTP on `127.0.0.1`,
   gated by a per-server console token and an `Origin` check. The browser is
   trusted to be the developer's own, but pages it loads are not.

Crossing any boundary requires a fresh check; nothing downstream is trusted
just because it came from upstream.

## Adversaries

### In scope

- **Other apps on the device.** Cannot read the sidekick `LocalSocket` (app
  sandbox), cannot read the session token (`run-as`-gated), and the bridge
  rejects requests with a missing or mismatched token.
- **Processes on the host that are not the developer's own.** Loopback bind
  prevents off-host reach; the console token gates mutating requests; only
  this user's processes can read the console asset that injects the token.
- **Web pages opened in the developer's browser.** Cannot read the token
  (cross-origin), and the `Origin` allow-list rejects mutating requests from
  anything other than `http://127.0.0.1:<port>` / `http://localhost:<port>`.

### Out of scope

- **Root on the device.** A rooted device can read any app's sandbox; we do
  not try to defend the sidekick against the OS itself.
- **The developer themselves.** FixThis is a tool the developer runs against
  their own app on their own machine. They can read their own screenshots.
- **Attackers with arbitrary code execution as the developer's user on the
  host.** Such an attacker already owns the MCP process, the console assets,
  and the `.fixthis/` directory; no application-layer guard meaningfully
  mitigates them.

## Mitigations today

Each control below points to the file that currently implements it. Line
numbers are intentionally omitted to keep this document stable as the code
moves; open the file and search for the named symbol.

Paths are repository-root relative.

| Boundary | Control | Implementation |
| --- | --- | --- |
| Host ↔ console | Bind to `127.0.0.1` by default (loopback only) | `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` |
| Browser ↔ console | Mutating `/api/*` routes require a local console `Origin`, local `Host` pinned to the running loopback port, and the per-server `X-FixThis-Console-Token` | `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRequestAuth.kt` |
| App ↔ bridge | Shared session token; bridge rejects requests with a missing or mismatched token (`UNAUTHORIZED`) | `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` |
| App ↔ bridge | Screenshot path validation — canonicalize and require the file is under the FixThis screenshot cache, with explicit client-supplied paths rejected | `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/PathSafety.kt`, `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeScreenshotReader.kt` |
| App ↔ bridge | Transport is an abstract-namespace `LocalSocket` (app-sandbox scoped, reachable via `adb run-as`) | `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` |
| Build-time | Debug-only manifest: sidekick startup provider is only merged into debug builds | `fixthis-compose-sidekick/src/debug/AndroidManifest.xml` |
| Runtime | `FLAG_DEBUGGABLE` runtime guard — the initializer returns early if the host app is not a debuggable build | `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/init/FixThisInitializer.kt` |

## Closed hardening tracked from the security plan

The original security-hardening plan (`docs/plans/security-hardening.md`)
tracked these as gaps; they are now implemented and covered by tests.

- **SEC-2 — Path-traversal hardening.** Screenshot-cache containment is
  centralized in `PathSafety` and applied at screenshot read time.
- **SEC-3 — Stale-socket recovery.** Bridge startup retries bounded suffix
  candidates and reports the resolved socket name to the host.
- **SEC-4 — Console mutation auth hardening.** Mutating console requests are
  guarded in `ConsoleRequestAuth`: read-only `GET` routes do not require the
  token, while mutating `/api/*` routes require a local `Origin` when present,
  a local `Host` pinned to the running loopback port when present, and a
  constant-time comparison against the per-server
  `X-FixThis-Console-Token`.

### Deferred follow-up

- None currently tracked from the original security-hardening plan.
