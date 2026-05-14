import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/toolModeFsm.js'), 'utf8');
const factory = new Function(`${src}; return {
  createEmptyToolMode,
  reduceToolMode,
  ToolMode,
};`);
const m = factory();

test('ToolMode enum exposes 3 states', () => {
  assert.equal(m.ToolMode.SELECT, 'SELECT');
  assert.equal(m.ToolMode.ANNOTATE_IDLE, 'ANNOTATE_IDLE');
  assert.equal(m.ToolMode.ANNOTATE_DRAGGING, 'ANNOTATE_DRAGGING');
});

test('initial state matches spec shape', () => {
  const s = m.createEmptyToolMode();
  assert.equal(s.mode, m.ToolMode.SELECT);
  assert.equal(s.annotationSequence, 1);
  assert.equal(s.hoveredTarget, null);
  assert.equal(s.drag, null);
  assert.equal(s.suppressNextClick, false);
  assert.equal(s.focusedSavedItemId, null);
  assert.equal(s.focusedSavedSessionId, null);
  assert.equal(s.focusedSavedScreenId, null);
  assert.equal(s.historyDrawerOpen, false);
  assert.equal(s.addItemsFlowStarting, false);
  assert.equal(s.newHistoryAnnotateModeStarting, false);
});

test('unknown action returns same input', () => {
  const s = m.createEmptyToolMode();
  const next = m.reduceToolMode(s, { type: 'NOPE' });
  assert.equal(next, s);
});

test('reducer returns frozen state', () => {
  const s = m.createEmptyToolMode();
  const next = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  assert.equal(Object.isFrozen(next), true);
});

test('ENTER_SELECT transitions to SELECT and clears hover/drag', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'SET_HOVERED_TARGET', target: { nodeUid: 'x' } });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  s = m.reduceToolMode(s, { type: 'ENTER_SELECT' });
  assert.equal(s.mode, m.ToolMode.SELECT);
  assert.equal(s.hoveredTarget, null);
  assert.equal(s.drag, null);
});

test('ENTER_ANNOTATE transitions to ANNOTATE_IDLE', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_IDLE);
});

test('START_DRAG only when ANNOTATE_*', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  // SELECT mode: drag remains null
  assert.equal(s.drag, null);
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 4, y: 5 } });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_DRAGGING);
  assert.deepEqual(s.drag.start, { x: 4, y: 5 });
  assert.equal(s.drag.preview, null);
});

test('UPDATE_DRAG_PREVIEW updates the preview rect during dragging', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  s = m.reduceToolMode(s, { type: 'UPDATE_DRAG_PREVIEW', preview: { left: 1, top: 2, right: 3, bottom: 4 } });
  assert.deepEqual(s.drag.preview, { left: 1, top: 2, right: 3, bottom: 4 });
});

test('DROP_COMMIT returns to ANNOTATE_IDLE, clears drag, increments annotationSequence', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  const before = s.annotationSequence;
  s = m.reduceToolMode(s, { type: 'DROP_COMMIT' });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_IDLE);
  assert.equal(s.drag, null);
  assert.equal(s.annotationSequence, before + 1);
});

test('DROP_DISCARD returns to ANNOTATE_IDLE, clears drag, no seq change', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  const before = s.annotationSequence;
  s = m.reduceToolMode(s, { type: 'DROP_DISCARD' });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_IDLE);
  assert.equal(s.drag, null);
  assert.equal(s.annotationSequence, before);
});

test('SET_HOVERED_TARGET sets hoveredTarget verbatim', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_HOVERED_TARGET', target: { nodeUid: 'abc' } });
  assert.deepEqual(s.hoveredTarget, { nodeUid: 'abc' });
  s = m.reduceToolMode(s, { type: 'SET_HOVERED_TARGET', target: null });
  assert.equal(s.hoveredTarget, null);
});

test('SET_SUPPRESS_NEXT_CLICK toggles flag', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_SUPPRESS_NEXT_CLICK', value: true });
  assert.equal(s.suppressNextClick, true);
  s = m.reduceToolMode(s, { type: 'SET_SUPPRESS_NEXT_CLICK', value: false });
  assert.equal(s.suppressNextClick, false);
});

test('TOGGLE_HISTORY_DRAWER flips the boolean', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'TOGGLE_HISTORY_DRAWER' });
  assert.equal(s.historyDrawerOpen, true);
  s = m.reduceToolMode(s, { type: 'TOGGLE_HISTORY_DRAWER' });
  assert.equal(s.historyDrawerOpen, false);
});

test('SET_HISTORY_DRAWER sets explicit value', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_HISTORY_DRAWER', open: true });
  assert.equal(s.historyDrawerOpen, true);
  s = m.reduceToolMode(s, { type: 'SET_HISTORY_DRAWER', open: false });
  assert.equal(s.historyDrawerOpen, false);
});

test('FOCUS_SAVED_ITEM preserves saved screen context independently of detail focus', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'FOCUS_SAVED_ITEM', itemId: 'item-1', sessionId: 'sess-2', screenId: 'screen-2' });
  assert.equal(s.focusedSavedItemId, 'item-1');
  assert.equal(s.focusedSavedSessionId, 'sess-2');
  assert.equal(s.focusedSavedScreenId, 'screen-2');
  s = m.reduceToolMode(s, { type: 'FOCUS_SAVED_ITEM', itemId: null, sessionId: 'sess-2', screenId: 'screen-2' });
  assert.equal(s.focusedSavedItemId, null);
  assert.equal(s.focusedSavedSessionId, 'sess-2');
  assert.equal(s.focusedSavedScreenId, 'screen-2');
  s = m.reduceToolMode(s, { type: 'FOCUS_SAVED_ITEM', itemId: null, sessionId: null });
  assert.equal(s.focusedSavedItemId, null);
  assert.equal(s.focusedSavedSessionId, null);
  assert.equal(s.focusedSavedScreenId, null);
});

test('SET_ADD_ITEMS_FLOW_STARTING toggles flag', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_ADD_ITEMS_FLOW_STARTING', value: true });
  assert.equal(s.addItemsFlowStarting, true);
  s = m.reduceToolMode(s, { type: 'SET_ADD_ITEMS_FLOW_STARTING', value: false });
  assert.equal(s.addItemsFlowStarting, false);
});

test('SET_NEW_HISTORY_ANNOTATE_MODE_STARTING toggles flag', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_NEW_HISTORY_ANNOTATE_MODE_STARTING', value: true });
  assert.equal(s.newHistoryAnnotateModeStarting, true);
});

test('ADVANCE_ANNOTATION_SEQ increments annotationSequence', () => {
  let s = m.createEmptyToolMode();
  const before = s.annotationSequence;
  s = m.reduceToolMode(s, { type: 'ADVANCE_ANNOTATION_SEQ' });
  assert.equal(s.annotationSequence, before + 1);
});

test('DROP_COMMIT is no-op when not dragging', () => {
  let s = m.createEmptyToolMode();
  const next = m.reduceToolMode(s, { type: 'DROP_COMMIT' });
  assert.equal(next, s);
  assert.equal(s.annotationSequence, 1);
});

test('DROP_DISCARD is no-op when not dragging and drag is null', () => {
  let s = m.createEmptyToolMode();
  const next = m.reduceToolMode(s, { type: 'DROP_DISCARD' });
  assert.equal(next, s);
});

test('SET_ANNOTATION_SEQUENCE_AT_LEAST raises sequence to max', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'SET_ANNOTATION_SEQUENCE_AT_LEAST', value: 10 });
  assert.equal(s.annotationSequence, 10);
  s = m.reduceToolMode(s, { type: 'SET_ANNOTATION_SEQUENCE_AT_LEAST', value: 3 });
  assert.equal(s.annotationSequence, 10);
});
