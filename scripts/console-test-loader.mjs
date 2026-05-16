import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseRequires, topoSort } from './build-console-assets.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
const sideEffectfulTestDependencies = new Set(['state.js', 'main.js']);

function readConsoleModule(name) {
  return readFileSync(resolve(sourceDir, name), 'utf8');
}

function dependencyGraphFor(requestedModules) {
  const graph = new Map();
  const visiting = [...requestedModules];
  while (visiting.length > 0) {
    const name = visiting.pop();
    if (graph.has(name)) continue;
    let content;
    try {
      content = readConsoleModule(name);
    } catch (_) {
      throw new Error(`Console module not found: ${name}`);
    }
    const deps = parseRequires(content);
    graph.set(name, { content, deps });
    for (const dep of deps) visiting.push(dep);
  }
  return graph;
}

export function consoleModuleSourceOrder(requestedModules) {
  return topoSort(dependencyGraphFor(requestedModules));
}

export function consoleModuleClosure(requestedModules) {
  const graph = dependencyGraphFor(requestedModules);
  return topoSort(graph)
    .map((name) => `//#region ${name}\n${graph.get(name).content.trimEnd()}\n//#endregion ${name}\n`)
    .join('\n');
}

export function loadConsoleSymbols({ modules, symbols, args = [], values = [] }) {
  const graph = dependencyGraphFor(modules);
  const requested = new Set(modules);
  const returnObject = symbols
    .map((symbol) => `${JSON.stringify(symbol)}: (typeof ${symbol} === 'undefined' ? undefined : ${symbol})`)
    .join(',\n');
  const prelude = `
const __consoleTestElement = {
  addEventListener() {},
  appendChild() {},
  blur() {},
  classList: { add() {}, remove() {}, toggle() {} },
  dataset: {},
  focus() {},
  querySelector() { return null; },
  querySelectorAll() { return []; },
  remove() {},
  removeAttribute() {},
  setAttribute() {},
  style: {},
};
var document = typeof document !== 'undefined' ? document : {
  activeElement: null,
  addEventListener() {},
  body: __consoleTestElement,
  createElement() { return { ...__consoleTestElement }; },
  getElementById() { return null; },
  hidden: false,
  querySelector() { return null; },
  querySelectorAll() { return []; },
  removeEventListener() {},
};
var window = typeof window !== 'undefined' ? window : {
  addEventListener() {},
  confirm() { return false; },
  FixThisConsoleConfig: {},
  removeEventListener() {},
  setTimeout: (fn, ms) => setTimeout(fn, ms),
};
var localStorage = typeof localStorage !== 'undefined' ? localStorage : {
  getItem() { return null; },
  removeItem() {},
  setItem() {},
};
var CSS = typeof CSS !== 'undefined' ? CSS : { escape(value) { return String(value); } };
var Node = typeof Node !== 'undefined' ? Node : { ELEMENT_NODE: 1 };
var createBlockedReasonDebouncer = typeof createBlockedReasonDebouncer === 'function'
  ? createBlockedReasonDebouncer
  : function createBlockedReasonDebouncerFallback() { return { record() { return null; }, reset() {} }; };
var createUnresponsiveTracker = typeof createUnresponsiveTracker === 'function'
  ? createUnresponsiveTracker
  : function createUnresponsiveTrackerFallback() { return { reset() {}, update() { return false; } }; };
var createConsoleApp = typeof createConsoleApp === 'function'
  ? createConsoleApp
  : function createConsoleAppFallback() {
    const useCases = {
      dispatch() {},
      getState() { return {}; },
      reset() {},
      start() {},
      stop() {},
    };
    return { connection: useCases, polling: useCases, preview: useCases, toolMode: useCases };
  };
`;
  const source = topoSort(graph)
    .filter((name) => requested.has(name) || !sideEffectfulTestDependencies.has(name))
    .map((name) => `//#region ${name}\n${graph.get(name).content.trimEnd()}\n//#endregion ${name}\n`)
    .join('\n');
  const factory = new Function(
    ...args,
    `${prelude}
${source}
const __result = {
${returnObject}
};
for (const [name, value] of Object.entries(__result)) {
  if (typeof value === 'undefined') throw new Error('Console symbol not found: ' + name);
}
return __result;`,
  );
  return factory(...values);
}
