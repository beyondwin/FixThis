// @requires (none)
let serverBuildChipNode = null;

function renderServerBuildChip(node) {
  const cfg = (typeof window !== 'undefined' && window.FixThisConsoleConfig) || {};
  node.dataset.state = 'connected';
  node.textContent = formatChipText('connected', cfg.buildHash);
}

function updateServerBuildChipState(node, { state, buildHash } = {}) {
  if (!node) return;
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

function mountServerBuildChip() {
  if (typeof document === 'undefined') return null;
  if (serverBuildChipNode) return serverBuildChipNode;
  const host = document.querySelector('.studio-context');
  if (!host) return null;
  const node = document.createElement('span');
  node.id = 'fixthis-server-build-chip';
  host.appendChild(node);
  renderServerBuildChip(node);
  serverBuildChipNode = node;
  return node;
}

function getServerBuildChipNode() {
  return serverBuildChipNode;
}
