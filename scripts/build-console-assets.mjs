#!/usr/bin/env node
import { execSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  statSync,
  watch,
  writeFileSync,
} from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';
import { build as esbuild } from 'esbuild';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');

export function consoleSourceFiles(rootDir) {
  const files = [];
  function walk(dir) {
    for (const entry of readdirSync(dir).sort()) {
      const absolute = resolve(dir, entry);
      const stat = statSync(absolute);
      if (stat.isDirectory()) {
        walk(absolute);
      } else if (entry.endsWith('.js')) {
        files.push(relative(rootDir, absolute).split('\\').join('/'));
      }
    }
  }
  walk(rootDir);
  return files.sort();
}

export function parseRequires(content) {
  const headerMatch = content.match(/^\s*\/\/\s*@requires\s+(.+)$/m);
  if (!headerMatch) return [];
  const value = headerMatch[1].trim();
  if (value === '(none)' || value === '') return [];
  return value.split(',').map((s) => s.trim()).filter(Boolean);
}

export function topoSort(g) {
  const visited = new Set();
  const visiting = new Set();
  const order = [];
  function visit(name, stack = []) {
    if (visited.has(name)) return;
    if (visiting.has(name)) {
      throw new Error(
        `Cycle in // @requires graph: ${[...stack, name].join(' -> ')}`,
      );
    }
    visiting.add(name);
    const node = g.get(name);
    if (!node) throw new Error(`Unknown module referenced: ${name}`);
    for (const dep of node.deps) visit(dep, [...stack, name]);
    visiting.delete(name);
    visited.add(name);
    order.push(name);
  }
  for (const name of [...g.keys()].sort()) visit(name);
  return order;
}

const CONTRACT_SYMBOLS = [
  // Core session-polling identifiers (grep'd by ConsoleFeedbackItemRoutesTest)
  'withMutationLock',
  'pollSessionsTick',
  'startSessionsPolling',
  'mergeSessionIntoState',
  'MaxConsecutivePollFailures',
  'DefaultLivePreviewIntervalMs',
  'PreviewIntervalStorageKey',
  // Polling FSM wiring (ConsoleFeedbackItemRoutesTest.pollingFsmIsWiredIntoMainBundle)
  'pollingUseCases',
  'createBrowserPollingUseCases',
  'lastSessionsEtag',
  'lastSessionEtag',
  'pendingMutationCount',
  'consecutiveFailures',
  // State / connection (ConsoleConnectionRoutesTest)
  'createInitialConnectionState',
  'createInitialPreviewState',
  // Staleness (ConsoleAssetRoutesTest)
  'checkServerStaleness',
  'renderStalenessBanner',
  'MinimumSupportedProtocolVersion',
  'checkProtocolCompat',
  'parseProtocolVersion',
  'compareProtocolVersion',
  'StaleThresholdMs',
  // Preview region rendering (ConsolePreviewRoutesTest)
  'renderPreviewRegion',
  'renderSessionRegions',
  'renderInspectorRegion',
  // Saved-evidence / annotation rendering (ConsoleFeedbackItemRoutesTest)
  'renderSavedEvidenceOverlay',
  'focusSavedEvidenceItem',
  'renderSavedEvidenceGroups',
  'renderComposerInspector',
  'renderSavedAnnotationsInspector',
  'renderAnnotationDetail',
  'resetComposer',
  // Pending items (ConsoleFeedbackItemRoutesTest)
  'renderPendingItems',
  'renderNumberedFeedbackOverlay',
  'focusPendingFeedbackItem',
  'deletePendingFeedbackItem',
  'startDraftAnnotationFlow',
  'createAnnotationFromSelection',
  'savePendingFeedbackItems',
  // Canvas selection (ConsoleFeedbackItemRoutesTest)
  'finishAreaSelection',
  'selectNodeAtPoint',
  'nodesForHitTest',
  'naturalPointFromEvent',
  // History panel (ConsoleFeedbackItemRoutesTest)
  'formatSessionLabel',
  'formatSessionSummary',
  'historyOpenCount',
  'historyDoneCount',
  'renderHistoryStrip',
  'formatItemLabel',
  'sessionOrdinalLookup',
  'stableHistorySessions',
  'renderSessionsList',
  'emptySessionsHtml',
  'historyStartAnnotatingItemHtml',
  'enterNewHistoryAnnotateMode',
  'deleteHistorySession',
  // Device control (ConsoleFeedbackItemRoutesTest / ConsoleConnectionRoutesTest)
  'refreshDevices',
  'selectDevice',
  'disconnectDevice',
  'deviceLabel',
  'shortenDeviceSerial',
  'setDeviceUiState',
  'deviceOptionLabel',
  // Top-bar actions (ConsoleFeedbackItemRoutesTest)
  'sendAgentPrompt',
  'clearSelection',
  'handleInspectorFooterAction',
  // Shortcuts (ConsoleFeedbackItemRoutesTest)
  'isTextInputFocused',
  'handleGlobalShortcut',
];

export function assertContractSymbols(output, symbols = CONTRACT_SYMBOLS) {
  for (const symbol of symbols) {
    if (!output.includes(symbol)) {
      throw new Error(
        `esbuild dropped contract symbol '${symbol}'. ` +
        `Add @preserve banner or stop minifying.`,
      );
    }
  }
}

// Use pathToFileURL so paths with spaces (e.g. ~/Library/Application Support/...)
// produce the same percent-encoded URL as import.meta.url.
const isMainModule = import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMainModule) {
  main().catch((e) => { console.error(e); process.exit(1); });
}

async function buildOnce({ reproducible } = {}) {
  const onDisk = consoleSourceFiles(sourceDir);

  for (const name of onDisk) {
    if (name === 'main.js') continue;
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\s*\/\/\s*@requires\s+/m.test(content)) {
      throw new Error(`fixthis-mcp/src/main/console/${name} has no // @requires header.`);
    }
  }

  const graph = new Map();
  for (const name of onDisk) {
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    graph.set(name, { content, deps: parseRequires(content) });
  }
  const sources = topoSort(graph);
  const entries = sources.map((name) => {
    const path = resolve(sourceDir, name);
    return `//#region ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n//#endregion ${name}\n`;
  });
  const concatenated = entries.join('\n');

  const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
  const mapPath = `${target}.map`;
  const metaPath = resolve(root, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');

  const epochMs = Math.floor(Date.now() / 60000) * 60000;
  let gitSha;
  try {
    gitSha = execSync('git rev-parse --short HEAD', {
      cwd: root, stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim();
  } catch (_) {
    gitSha = 'unknown';
  }
  const meta = reproducible
    ? { buildEpochMs: 0, gitSha: 'reproducible' }
    : { buildEpochMs: epochMs, gitSha };
  const metaSerialized = JSON.stringify(meta, null, 2) + '\n';

  const result = await esbuild({
    stdin: { contents: concatenated, loader: 'js', sourcefile: 'app.js' },
    bundle: false, write: false, outfile: target, minify: false,
    minifyWhitespace: true, minifySyntax: true, minifyIdentifiers: true,
    target: ['es2020'], sourcemap: 'linked', legalComments: 'inline',
  });
  const jsBytes = result.outputFiles.find((f) => f.path.endsWith('.js')).contents;
  const mapBytes = result.outputFiles.find((f) => f.path.endsWith('.map')).contents;

  const jsText = new TextDecoder().decode(jsBytes);
  assertContractSymbols(jsText);

  // Runtime diagnostics plus its serialized policy mutation queue adds 6,886
  // raw bytes over the prior reproducible asset, with narrow repair headroom.
  const RAW_BUDGET_BYTES = 239_000;
  const GZIP_BUDGET_BYTES = 60_500;
  if (jsBytes.byteLength > RAW_BUDGET_BYTES) {
    throw new Error(`Bundle (raw) is ${jsBytes.byteLength} bytes, exceeds raw budget of ${RAW_BUDGET_BYTES} bytes.`);
  }
  const gzBytes = gzipSync(Buffer.from(jsBytes), { level: 9 }).byteLength;
  if (gzBytes > GZIP_BUDGET_BYTES) {
    throw new Error(`Bundle (gzipped) is ${gzBytes} bytes, exceeds gzip budget of ${GZIP_BUDGET_BYTES} bytes.`);
  }

  return { target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized };
}

function atomicWrite(path, bytes) {
  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(tmp, bytes);
  renameSync(tmp, path);
}

function commitArtifacts({ target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized }) {
  mkdirSync(dirname(target), { recursive: true });
  atomicWrite(target, jsBytes);
  atomicWrite(mapPath, mapBytes);
  atomicWrite(metaPath, metaSerialized);
}

function runCheck(built) {
  const { target, mapPath, metaPath, jsBytes, mapBytes, metaSerialized } = built;
  if (!existsSync(target)) {
    console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!Buffer.from(jsBytes).equals(readFileSync(target))) {
    console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!existsSync(mapPath) || !Buffer.from(mapBytes).equals(readFileSync(mapPath))) {
    console.error('Generated console app.js.map is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  if (!existsSync(metaPath) || readFileSync(metaPath, 'utf8') !== metaSerialized) {
    console.error('Generated console-build-meta.json is out of date. Run FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  process.exit(0);
}

async function runWatch() {
  const debounceMs = 100;
  let timer = null;
  const rebuild = async () => {
    try {
      const built = await buildOnce({
        reproducible: process.env.FIXTHIS_BUNDLE_REPRODUCIBLE === '1',
      });
      commitArtifacts(built);
      console.log(`[watch] rebuilt at ${new Date().toISOString()}`);
    } catch (err) {
      console.error(`[watch] rebuild failed: ${err.message}`);
    }
  };
  const schedule = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(rebuild, debounceMs);
  };
  await rebuild();
  const watcher = watch(sourceDir, { recursive: true }, (_event, filename) => {
    if (filename && filename.endsWith('.js')) schedule();
  });
  process.on('SIGINT', () => { watcher.close(); process.exit(0); });
  process.on('SIGTERM', () => { watcher.close(); process.exit(0); });
}

async function main() {
  if (process.argv.includes('--watch')) {
    await runWatch();
    return;
  }
  const built = await buildOnce({
    reproducible: process.env.FIXTHIS_BUNDLE_REPRODUCIBLE === '1'
      || process.argv.includes('--check'),
  });
  if (process.argv.includes('--check')) {
    runCheck(built);
    return;
  }
  commitArtifacts(built);
}
