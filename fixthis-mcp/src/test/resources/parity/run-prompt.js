#!/usr/bin/env node
// Test-only Node harness. Loads checked-in prompt.js inside a sandboxed
// vm.createContext with a minimal stub global. Not used in production.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const sessionPath = process.argv[2];
if (!sessionPath) {
  console.error('Usage: node run-prompt.js <session.json>');
  process.exit(2);
}

const session = JSON.parse(fs.readFileSync(sessionPath, 'utf8'));
const promptSourcePath = path.join(__dirname, '..', '..', '..', 'main', 'console', 'prompt.js');
const promptSource = fs.readFileSync(promptSourcePath, 'utf8');

const sandbox = {
  state: { session: session },
  error: { textContent: '' },
  promptActionInFlight: false,
  console: console,
  // Stubs for environment-dependent helpers prompt.js relies on:
  currentPromptAnnotations: () => session.items || [],
  selectedHistorySummary: () => null,
  formatSessionLabel: () => '',
  boundsForTarget: (target) => (target && target.boundsInWindow) || null,
  formatBounds: (b) => b.left + ',' + b.top + ',' + b.right + ',' + b.bottom,
  Math: Math, Number: Number, Object: Object, String: String, Array: Array,
  Set: Set, Map: Map
};

const context = vm.createContext(sandbox);
vm.runInContext(promptSource, context, { filename: promptSourcePath });

const out = context.currentAnnotationsPromptCompact(session.items || []);
process.stdout.write(out + (out.endsWith('\n') ? '' : '\n'));
