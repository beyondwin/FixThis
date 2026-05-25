// @requires (none)
function renderServerBuildChip(node) {
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  node.dataset.state = 'connected';
  node.textContent = formatChipText('connected', cfg.buildHash);
}

function updateServerBuildChipState(node, { state, buildHash } = {}) {
  if (state) node.dataset.state = state;
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  const hash = buildHash ?? cfg.buildHash;
  node.textContent = formatChipText(node.dataset.state, hash);
}

function formatChipText(state, buildHash) {
  if (state === 'reconnecting') return 'reconnecting…';
  if (state === 'connected' && buildHash) return `connected · build sha=${buildHash}`;
  if (state === 'connected') return 'connected';
  return state || '';
}
