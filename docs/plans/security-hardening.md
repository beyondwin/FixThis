# Plan — Security Hardening

Status: Draft
Spec: [`../specs/security-hardening.md`](../specs/security-hardening.md)

Order is documentation → low-risk hardening → behavioural change.

Note: SEC-4 (Token + origin gate) is deferred. The current
`FeedbackConsoleServer` already binds to `127.0.0.1`, performs
`X-FixThis-Token` and Origin checks. The remaining SEC-4 delta (Host-header
check, constant-time comparison, `ConsoleAuth.kt` extraction, contract
doc) is tracked as a follow-up.

## Tasks

### Task 0: SEC-1 — Threat model document

**Files:**
- `docs/reference/threat-model.md` (new)
- `README.md` (link from Privacy & security row)
- `docs/index.md` (add entry)
- `SECURITY.md` (cross-link to threat model)

**Steps**
1. Use the template:
   - **Assets** (4 bullets)
   - **Trust boundaries** (numbered diagram in ASCII)
   - **In-scope / out-of-scope adversaries** (2 bullet lists)
   - **Mitigations today** (table: control → file:line)
   - **Open gaps** (links to SEC-2/3 once merged; SEC-4 marked as deferred follow-up)
2. Cross-link from `SECURITY.md` for vulnerability reporting flow.

#### Acceptance Criteria
```bash
test -f docs/reference/threat-model.md
grep -q 'threat-model' README.md
grep -q 'threat-model' docs/index.md
grep -q 'threat-model' SECURITY.md
# All file:line citations in the threat model must resolve to existing files
grep -oE '[a-zA-Z0-9_/.-]+\.(kt|kts|xml|md|js|toml)(:[0-9]+)?' docs/reference/threat-model.md | \
  sed 's/:[0-9]*$//' | sort -u | while read -r f; do test -f "$f" || (echo "missing: $f"; false); done
```

### Task 1: SEC-2 — Path validation hardening

**Files:**
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/PathSafety.kt` (new — extract `isUnder`)
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/PathSafetyTest.kt` (new)
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerScreenshotPathTest.kt` (new)

**Steps**
1. Extract `isUnder` into `PathSafety.isUnder(child: File, parent: File): Boolean`
   with canonical-path semantics; document TOCTOU caveat in a single-line kdoc.
2. Move the cache-dir resolution inside the same `withContext(ioDispatcher)`
   block; verify the path immediately before `readBytes`.
3. Write parameterised tests for:
   `../../etc/passwd`, `./../foo`, symlink that resolves outside, trailing
   slash, repeated separators, and an absolute path equal to the cache root.

#### Acceptance Criteria
```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
test -f fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/PathSafety.kt
test -f fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/PathSafetyTest.kt
test -f fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerScreenshotPathTest.kt
```

### Task 2: SEC-3 — `LocalServerSocket` resilience

**Files:**
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeSocketNameNegotiator.kt` (new)
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt` (bump VERSION; source of truth)
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt` (accept negotiated suffix; mirror VERSION constant)
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt` (mirror VERSION constant)
- `fixthis-mcp/src/main/console/staleness.js` (mirror VERSION constant — JS-side)
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerStartupTest.kt` (new)

Note: the protocol-version sync test (`fixthis-mcp/src/test/.../BridgeProtocolVersionSyncTest.kt`) enforces equality across all 4 mirror sites (BridgeProtocol.kt, BridgeClient.kt, ServerVersionRoutes.kt, staleness.js). All 4 must bump together. `BridgeClient.kt` lives under `:fixthis-cli`, not `:fixthis-mcp`.

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

#### Acceptance Criteria
```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
./gradlew :fixthis-mcp:test --tests '*BridgeProtocolVersionSync*' --no-daemon
test -f fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeSocketNameNegotiator.kt
test -f fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerStartupTest.kt
```

## Rollout

- Task 0: SEC-1 first (documentation, no code).
- Task 1, Task 2: additive and behind unit tests.
- SEC-4 (token + origin gate) deferred — see note at top of file.
