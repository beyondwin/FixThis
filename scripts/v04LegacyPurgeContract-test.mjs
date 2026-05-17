import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function assertNoPattern(path, pattern, reason) {
  assert.doesNotMatch(source(path), pattern, `${path}: ${reason}`);
}

test('browser console no longer exposes schema-v1 pending mirror APIs', () => {
  assert.equal(
    existsSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js')),
    false,
    'pendingPersistence.js is schema-v1 pending mirror support and must stay removed',
  );
  for (const path of [
    'fixthis-mcp/src/main/console/draftPorts.js',
    'fixthis-mcp/src/main/console/draftStorageAdapter.js',
    'fixthis-mcp/src/main/console/main.js',
    'fixthis-mcp/src/main/console/history.js',
    'fixthis-mcp/src/main/console/annotations.js',
    'fixthis-mcp/src/main/console/pendingRecoveryUi.js',
  ]) {
    assertNoPattern(path, /fixthis\.pending|migrateLegacyPending|clearLegacyPending|restorePendingState|persistPendingState|clearPendingMirror|activePendingMirrorSessions/, 'pre-v0.4 pending mirror support is unsupported');
  }
});

test('console screenshot routes do not allow old artifact roots', () => {
  for (const path of [
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt',
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt',
  ]) {
    assertNoPattern(path, /\.fixthis\/artifacts|legacyRoot|legacyArtifactsDir/, 'pre-v0.4 screenshot artifact fallback is unsupported');
  }
});

test('session store does not dedupe retries using pre-client-key semantic fallback', () => {
  assert.equal(
    existsSync(resolve(root, 'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreSemanticDeduplication.kt')),
    false,
    'semantic duplicate fallback for old draft items must stay removed',
  );
  for (const path of [
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt',
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt',
  ]) {
    assertNoPattern(path, /legacySemanticDraftKey|incomingSemanticDraftKey|existingLegacySemanticKeys|semanticDraftKey/, 'current idempotency uses client draft keys only');
  }
});

test('MCP session service exposes item-id-aware handoff only', () => {
  assertNoPattern(
    'fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt',
    /fun sendDraftToAgent\(sessionId: String\): SessionDto/,
    'deprecated no-item handoff overload must stay removed',
  );
});

test('source matching uses untyped fallback terminology internally', () => {
  // Scoped to SourceMatchReason only. The unrelated enums
  // SourceCandidateRisk.LEGACY_FALLBACK, SourceHint.LEGACY_FALLBACK, and
  // SourceHintRisk.LEGACY_FALLBACK are intentionally out of scope for phase 1.
  assertNoPattern(
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt',
    /LEGACY_FALLBACK/,
    'internal SourceMatchReason enum name should not call current fallback behavior legacy',
  );
  assert.match(
    source('fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt'),
    /UNTYPED_FALLBACK\("legacy fallback"\)/,
    'wire label remains backward-compatible while internal terminology is current',
  );
  for (const path of [
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt',
    'fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt',
  ]) {
    assertNoPattern(
      path,
      /SourceMatchReason\.LEGACY_FALLBACK/,
      'every SourceMatchReason.LEGACY_FALLBACK reference must be renamed to SourceMatchReason.UNTYPED_FALLBACK',
    );
  }
});
