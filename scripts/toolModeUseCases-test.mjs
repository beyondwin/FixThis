import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fsmSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/toolModeFsm.js'), 'utf8');
const ucSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/toolModeUseCases.js'), 'utf8');
const factory = new Function(`${fsmSrc}\n${ucSrc}; return {
  createEmptyToolMode,
  reduceToolMode,
  createToolModeUseCases,
  nextAnnotationSequenceFromPendingItems,
  ToolMode,
};`);
const m = factory();

function make(observer = () => {}) {
  return m.createToolModeUseCases({ onChange: observer });
}

test('createToolModeUseCases exposes getState seeded with initial state', () => {
  const uc = make();
  const s = uc.getState();
  assert.equal(s.mode, m.ToolMode.SELECT);
  assert.equal(s.annotationSequence, 1);
});

test('enterAnnotate / enterSelect dispatch transitions', () => {
  const uc = make();
  uc.enterAnnotate();
  assert.equal(uc.getState().mode, m.ToolMode.ANNOTATE_IDLE);
  uc.enterSelect();
  assert.equal(uc.getState().mode, m.ToolMode.SELECT);
});

test('startDrag / updateDragPreview / dropCommit / dropDiscard', () => {
  const uc = make();
  uc.enterAnnotate();
  uc.startDrag({ x: 1, y: 2 });
  assert.equal(uc.getState().mode, m.ToolMode.ANNOTATE_DRAGGING);
  assert.deepEqual(uc.getState().drag.start, { x: 1, y: 2 });
  uc.updateDragPreview({ left: 0, top: 0, right: 5, bottom: 5 });
  assert.deepEqual(uc.getState().drag.preview, { left: 0, top: 0, right: 5, bottom: 5 });
  const before = uc.getState().annotationSequence;
  uc.dropCommit();
  assert.equal(uc.getState().mode, m.ToolMode.ANNOTATE_IDLE);
  assert.equal(uc.getState().drag, null);
  assert.equal(uc.getState().annotationSequence, before + 1);

  uc.startDrag({ x: 9, y: 9 });
  uc.dropDiscard();
  assert.equal(uc.getState().mode, m.ToolMode.ANNOTATE_IDLE);
  assert.equal(uc.getState().drag, null);
});

test('hover / suppressNextClick / history drawer setters work', () => {
  const uc = make();
  uc.setHoveredTarget({ nodeUid: 'q' });
  assert.deepEqual(uc.getState().hoveredTarget, { nodeUid: 'q' });
  uc.setSuppressNextClick(true);
  assert.equal(uc.getState().suppressNextClick, true);
  uc.toggleHistoryDrawer();
  assert.equal(uc.getState().historyDrawerOpen, true);
  uc.setHistoryDrawer(false);
  assert.equal(uc.getState().historyDrawerOpen, false);
});

test('focusSavedItem / setAddItemsFlowStarting / setNewHistoryAnnotateModeStarting', () => {
  const uc = make();
  uc.focusSavedItem('item-9', 'sess-9', 'screen-9');
  assert.equal(uc.getState().focusedSavedItemId, 'item-9');
  assert.equal(uc.getState().focusedSavedSessionId, 'sess-9');
  assert.equal(uc.getState().focusedSavedScreenId, 'screen-9');
  uc.focusSavedItem(null, 'sess-9', 'screen-9');
  assert.equal(uc.getState().focusedSavedItemId, null);
  assert.equal(uc.getState().focusedSavedSessionId, 'sess-9');
  assert.equal(uc.getState().focusedSavedScreenId, 'screen-9');
  uc.setAddItemsFlowStarting(true);
  assert.equal(uc.getState().draftFlowStarting, true);
  uc.setNewHistoryAnnotateModeStarting(true);
  assert.equal(uc.getState().newHistoryAnnotateModeStarting, true);
});

test('nextAnnotationSeq returns pre-increment value and advances', () => {
  const uc = make();
  const v1 = uc.nextAnnotationSeq();
  assert.equal(v1, 1);
  assert.equal(uc.getState().annotationSequence, 2);
  const v2 = uc.nextAnnotationSeq();
  assert.equal(v2, 2);
  assert.equal(uc.getState().annotationSequence, 3);
});

test('setAnnotationSequenceAtLeast raises but never lowers', () => {
  const uc = make();
  uc.setAnnotationSequenceAtLeast(10);
  assert.equal(uc.getState().annotationSequence, 10);
  uc.setAnnotationSequenceAtLeast(5);
  assert.equal(uc.getState().annotationSequence, 10);
});

test('selectors: isSelectMode, isAnnotateMode, isDragging', () => {
  const uc = make();
  assert.equal(uc.isSelectMode(), true);
  assert.equal(uc.isAnnotateMode(), false);
  assert.equal(uc.isDragging(), false);
  uc.enterAnnotate();
  assert.equal(uc.isSelectMode(), false);
  assert.equal(uc.isAnnotateMode(), true);
  assert.equal(uc.isDragging(), false);
  uc.startDrag({ x: 0, y: 0 });
  assert.equal(uc.isAnnotateMode(), true);
  assert.equal(uc.isDragging(), true);
});

test('onChange fires on dispatched action', () => {
  let count = 0;
  const uc = m.createToolModeUseCases({ onChange: () => { count++; } });
  uc.enterAnnotate();
  uc.startDrag({ x: 1, y: 1 });
  assert.equal(count >= 2, true);
});

test('recovered draft item ids advance annotation sequence', () => {
  const next = m.nextAnnotationSequenceFromPendingItems([
    { draftItemId: 'draft-1' },
    { draftItemId: 'draft-2' },
    { draftItemId: 'draft-3' },
  ], 1);

  assert.equal(next, 4);
});

test('legacy local annotation ids also advance annotation sequence', () => {
  const next = m.nextAnnotationSequenceFromPendingItems([
    { annotationId: 'local-8' },
    { draftItemId: 'draft-3' },
  ], 2);

  assert.equal(next, 9);
});

test('tool mode can be raised after recovering draft ids', () => {
  const uc = make();
  uc.setAnnotationSequenceAtLeast(m.nextAnnotationSequenceFromPendingItems([
    { draftItemId: 'draft-1' },
    { draftItemId: 'draft-2' },
    { draftItemId: 'draft-3' },
  ], uc.getState().annotationSequence));

  assert.equal(uc.nextAnnotationSeq(), 4);
  assert.equal(uc.nextAnnotationSeq(), 5);
});
