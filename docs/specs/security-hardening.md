# Spec — Security Hardening

Status: Draft
Scope: `:fixthis-compose-sidekick` bridge, `:fixthis-mcp` HTTP console
Related plan: [`../plans/security-hardening.md`](../plans/security-hardening.md)

## Background

FixThis is a local-only ADB tool: the bridge runs over a process-local
`LocalSocket` and the console binds to `127.0.0.1`. The current implementation
relies on these transport choices for safety. We want the **principal
guarantees** (no remote access, no path escape, no cross-app abuse on the
same device) to be explicit and tested rather than implicit.

## Goals

- Publish a short, readable threat model so contributors and downstream users
  can reason about what FixThis does and does not protect against.
- Make the screenshot-path traversal check robust against symlinked cache
  directories.
- Make `LocalSocket` startup resilient to stale sockets (recover instead of
  staying unreachable after a crash).
- Add an authentication / origin check on the local HTTP console so a
  malicious page in the user's browser cannot drive the bridge via DNS
  rebinding.

## Non-Goals

- Network-attached deployments (out of scope for V1).
- Encrypting on-disk artifacts under `.fixthis/`.
- Defending against an attacker with full local code execution as the user.

## Items

### SEC-1 — Threat model document

A one-page `docs/reference/threat-model.md` covering:

- **Assets:** screenshots, semantics trees, source-candidate paths, MCP
  feedback queue.
- **Trust boundaries:** debug app process ↔ ADB bridge ↔ host MCP process
  ↔ local browser console.
- **In-scope adversaries:** other apps on the device; processes on the host
  that are not the user's own; web pages opened in the user's browser.
- **Out-of-scope adversaries:** root on the device; the user themselves;
  attackers with arbitrary code execution as the user.
- **Mitigations** today (debug-only, local-loopback HTTP, ADB transport)
  and **gaps** that this spec closes (path-traversal hardening, stale-socket
  recovery, origin pinning).

**Acceptance:** doc merged and linked from README ("Privacy & security").

### SEC-2 — Screenshot path validation hardening

**Site:** `fixthis-compose-sidekick/.../bridge/BridgeServer.kt:182`
(`require(file.isUnder(cacheDirectory))`).

The existing check uses `canonicalFile` on both sides, which already resolves
symlinks. The remaining gaps are:

- The check happens **once** before opening; if the path is read from a
  long-lived snapshot, a TOCTOU window exists between resolve and read.
- `isUnder` is a project extension — its implementation should be co-located
  with the call site and unit-tested for `..`, repeated separators, and
  symlink targets that point outside the cache.

**Contract:**
- Resolve the path inside the same `withContext(ioDispatcher)` block that
  opens the file, then assert the open `FileChannel`'s path is canonically
  under the cache dir.
- `isUnder` covered by parameterised tests including:
  `../../etc/passwd`, `./cache/../../etc/passwd`, a symlink target outside
  the cache, and a path with a trailing slash.

**Acceptance:** new test class
`BridgeServerScreenshotPathTest` enumerates the cases above and passes; no
behaviour change for legitimate paths.

### SEC-3 — `LocalServerSocket` stale-socket recovery

**Site:** `fixthis-compose-sidekick/.../bridge/BridgeServer.kt:54`
(`LocalServerSocket(session.socketName)`).

After a crash, the abstract socket name may still be in use. Today the
sidekick logs and stays unreachable until the process restarts cleanly.

**Contract:**
- On bind failure, retry once after a short backoff with a different
  fallback suffix (`<name>-1`, `<name>-2`), bounded to three attempts.
- The chosen name is reported to the host through the bridge handshake so
  the MCP server can find the right socket; the host falls back to scanning
  the configured suffixes.
- On three failures, fail loudly with a diagnostic that includes the bound
  name (so users can `adb shell ss -lx | grep fixthis`).

**Acceptance:**
1. Unit test (Robolectric) that simulates an in-use socket name and asserts
   `BridgeServer.start()` succeeds on the second attempt.
2. MCP-side test that verifies the bridge handshake transmits the chosen
   name and the host resolves it correctly.

### SEC-4 — Origin / token gate on the console HTTP server

**Site:** `fixthis-mcp` HTTP console (`FeedbackConsoleServer.kt` and bridge
endpoints).

`127.0.0.1` binding alone does not protect against:
- DNS rebinding: a malicious page resolves an attacker-controlled hostname
  to `127.0.0.1` on the second request.
- A browser extension or page on the user's machine that has been told the
  console port.

**Contract:**
- On startup, generate a 128-bit random session token; print it in the
  console URL (`http://127.0.0.1:<port>/?t=<token>`). The token is also
  required on every bridge-action endpoint as either a query parameter or
  `X-FixThis-Token` header.
- Validate the `Host` header is `127.0.0.1` or `localhost`; reject otherwise.
- All state-changing endpoints (those that drive the device) must validate
  the token; read-only static asset routes may remain open.

**Acceptance:**
1. Manual test with `curl http://127.0.0.1:<port>/api/... -H 'Host: evil.tld'`
   → 403.
2. `curl` without the token to a state-changing endpoint → 401.
3. Existing browser flow keeps working because the URL FixThis prints
   already includes the token query.

## Out-of-scope follow-ups

- Encrypting `.fixthis/` artifacts at rest.
- Multi-user host (one host serving feedback for multiple developers).
- Signed manifest integrity for the console bundle.
