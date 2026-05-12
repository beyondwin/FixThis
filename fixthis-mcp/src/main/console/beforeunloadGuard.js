// beforeunloadGuard.js — ALH-1: pure decision helper for the
// beforeunload prompt. The window.addEventListener call lives in
// main.js and delegates here.

function shouldGuardUnload(pendingItemsCount) {
  return Number(pendingItemsCount) > 0;
}
