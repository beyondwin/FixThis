# Plan — Security Hardening

Status: Draft
Spec: [`../specs/security-hardening.md`](../specs/security-hardening.md)

Order is documentation → low-risk hardening → user-visible behaviour change.

## SEC-1 — Threat model document

**Files**
- New: `docs/reference/threat-model.md`
- `README.md` (link from Privacy & security row)
- `docs/index.md` (add entry)

**Steps**
1. Use the template:
   - **Assets** (4 bullets)
   - **Trust boundaries** (numbered diagram in ASCII)
   - **In-scope / out-of-scope adversaries** (2 bullet lists)
   - **Mitigations today** (table: control → file:line)
   - **Open gaps** (links to SEC-2/3/4 once merged)
2. Cross-link from `SECURITY.md` for vulnerability reporting flow.

**Validation**
- Manual review.
- All file-line citations resolve (`xargs -n1 ... -- test -f`).

## SEC-2 — Path validation hardening

**Files**
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- `fixthis-compose-sidekick/src/main/kotlin/.../PathSafety.kt` (extract
  `isUnder` here with a kdoc explaining what it does and does not protect
  against — TOCTOU explicitly called out)
- New: `fixthis-compose-sidekick/src/test/kotlin/.../PathSafetyTest.kt`
- New: `fixthis-compose-sidekick/src/test/kotlin/.../BridgeServerScreenshotPathTest.kt`

**Steps**
1. Extract `isUnder` into `PathSafety.isUnder(child: File, parent: File): Boolean`
   with canonical-path semantics; document TOCTOU caveat.
2. Move the cache-dir resolution inside the same `withContext(ioDispatcher)`
   block; verify the path immediately before `readBytes`.
3. Write parameterised tests for:
   `../../etc/passwd`, `./../foo`, symlink that resolves outside, trailing
   slash, repeated separators, and an absolute path equal to the cache root.

**Validation**
- `./gradlew :fixthis-compose-sidekick:testDebugUnitTest`
- New tests cover the listed inputs.

## SEC-3 — `LocalServerSocket` resilience

**Files**
- `fixthis-compose-sidekick/.../bridge/BridgeServer.kt`
- `fixthis-compose-sidekick/.../bridge/BridgeSocketNameNegotiator.kt` (new)
- `fixthis-mcp/.../bridge/BridgeClient.kt` — accept negotiated suffix
- New: `fixthis-compose-sidekick/src/test/kotlin/.../BridgeServerStartupTest.kt`

**Steps**
1. Introduce `BridgeSocketNameNegotiator` with `nextCandidate(base, attempt)`.
2. Wrap the `LocalServerSocket(name)` constructor in a loop:
   - `attempt 0..2`; on `IOException` move to next candidate.
   - On success, store the resolved name in the session metadata.
3. Update the handshake to include the resolved name; update `BridgeClient`
   to try `name`, `name-1`, `name-2` in order.
4. Bump `BridgeProtocol.VERSION` per
   `docs/reference/bridge-protocol.md`; update the mirrored constants and
   ensure `BridgeProtocolVersionSyncTest` catches it.

**Validation**
- Unit test simulating an `IOException` on attempt 0 and success on attempt 1.
- Manual: kill `fixthis-mcp`, immediately restart, sidekick still attaches.
- `:fixthis-mcp:test` includes `BridgeProtocolVersionSyncTest` green.

## SEC-4 — Token + origin gate

**Files**
- `fixthis-mcp/.../console/FeedbackConsoleServer.kt`
- `fixthis-mcp/.../console/ConsoleAuth.kt` (new — token storage, accessor)
- `fixthis-mcp/src/main/resources/console/app.js` — read token from URL once,
  send via `X-FixThis-Token` header on every state-changing fetch
- `docs/reference/feedback-console-contract.md` — document the auth contract
- New: `fixthis-mcp/src/test/kotlin/.../ConsoleAuthTest.kt`

**Steps**
1. Generate the session token in `FeedbackConsoleServer.start()`
   (`SecureRandom.nextBytes(16)` → base64url, 22 chars).
2. Print the full URL (with `?t=...`) to stdout; this is what
   `fixthis_open_feedback_console` shows.
3. Add a filter wrapping every state-changing route:
   - Verify `Host` is `127.0.0.1`, `localhost`, or the equivalent IPv6.
   - Verify the token is present and equal (constant-time comparison).
4. Update `app.js` to lift the token from `location.search` on first load
   and store it in memory; attach it as an `X-FixThis-Token` header on every
   subsequent fetch. Strip the token from the URL after loading.

**Validation**
- Unit test: state-changing endpoint with bad token → 401, with bad Host
  → 403, with valid both → 200.
- Manual: open the console URL, copy without the `?t=...`, paste in a new
  tab → console is read-only and shows a "session token required" banner.
- End-to-end smoke (`scripts/console-availability-test.mjs`) updated to
  carry the token.

## Rollout

- SEC-1 first (documentation, no code).
- SEC-2, SEC-3 are additive and behind unit tests.
- SEC-4 is the one user-visible change — note in CHANGELOG under "Security"
  with a migration note: copy the full URL including `?t=...`.
