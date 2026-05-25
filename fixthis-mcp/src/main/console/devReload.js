// @requires (none)
function handleConsoleAssetsChanged(payload) {
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  if (cfg.devReloadEnabled !== true) return;
  const incoming = payload && payload.buildHash;
  if (!incoming) return;
  if (incoming === cfg.buildHash) return;
  console.log('[devReload] bundle hash changed', cfg.buildHash, '->', incoming, '— reloading');
  location.reload();
}
