// @requires state.js
// previewStaleness.js — SIF-5: pure decision helper for time-based and
// disconnect-based preview staleness. A frozen preview becomes stale when
// either (a) it is older than MAX_PREVIEW_AGE_MS, or (b) the bridge is not
// in the "connected" state. The status-tick site in main.js / connection.js
// is responsible for calling evaluateStale on each refresh and writing the
// result back into state.preview.stale.
//
// NOTE: This file is intentionally separate from staleness.js, which handles
// build-epoch / protocol-version drift between the server JAR and the bundled
// console assets — an unrelated concern.

const MAX_PREVIEW_AGE_MS = 30000;

function evaluateStale(state, now) {
  const frozenAt = state && state.preview && state.preview.frozenAtEpochMillis;
  if (!frozenAt) return false;
  const age = now - frozenAt;
  if (age > MAX_PREVIEW_AGE_MS) return true;
  const connection = state && state.bridgeStatus && state.bridgeStatus.connection;
  if (connection !== 'connected') return true;
  return false;
}
