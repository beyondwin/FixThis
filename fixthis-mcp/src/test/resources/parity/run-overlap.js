#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const promptSourcePath = path.join(__dirname, '..', '..', '..', 'main', 'console', 'prompt.js');
const promptSource = fs.readFileSync(promptSourcePath, 'utf8');
const sandbox = {
  state: { session: { items: [] } },
  promptItemBounds: (item) => (item.target && item.target.boundsInWindow) || null,
  Math: Math, Number: Number, Object: Object, String: String, Array: Array, Set: Set, Map: Map,
  console: console
};
const context = vm.createContext(sandbox);
vm.runInContext(promptSource, context, { filename: promptSourcePath });

const items = [
  { target: { type: 'visual_area', boundsInWindow: { left: 0, top: 0, right: 100, bottom: 100 } } },
  { target: { type: 'visual_area', boundsInWindow: { left: 99, top: 99, right: 200, bottom: 200 } } }
];
const groups = context.compactDetectOverlap(items);
if (groups.length !== 1) {
  console.error('expected 1 overlap group, got ' + groups.length);
  process.exit(1);
}
console.log('OK');
