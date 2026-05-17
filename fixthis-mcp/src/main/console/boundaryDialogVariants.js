// @requires (none)
// boundaryDialogVariants.js - labels and rendering for #sessionBoundarySheet.

const BoundaryDialogVariants = Object.freeze({
  sessionSwitch: Object.freeze({
    title: () => 'Save draft before switching?',
    summary: (ctx = {}) => {
      const current = ctx.currentSessionName || 'current session';
      return '1 unsaved draft in ' + current + (ctx.targetSessionName ? ', switching to ' + ctx.targetSessionName + '.' : '.');
    },
    primary: Object.freeze({ label: 'Save and switch', action: 'saveAndProceed' }),
    secondary: Object.freeze({ label: 'Discard', action: 'discardAndProceed' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  sessionCreate: Object.freeze({
    title: () => 'Save draft before creating new session?',
    summary: () => '1 unsaved draft.',
    primary: Object.freeze({ label: 'Save and create', action: 'saveAndProceed' }),
    secondary: Object.freeze({ label: 'Discard', action: 'discardAndProceed' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  sessionDelete: Object.freeze({
    title: (ctx = {}) => 'Delete ' + (ctx.currentSessionName || 'current session') + '?',
    summary: (ctx = {}) => 'Removes ' + (ctx.annotationCount ?? 0) + ' annotations across ' + (ctx.screenCount ?? 0) + ' screens. Cannot be undone.',
    primary: Object.freeze({ label: 'Save and delete', action: 'saveAndProceed' }),
    secondary: Object.freeze({ label: 'Discard', action: 'discardAndProceed' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  editorBack: Object.freeze({
    title: () => 'Save draft before going back?',
    summary: () => '1 unsaved draft.',
    primary: Object.freeze({ label: 'Save and back', action: 'saveAndProceed' }),
    secondary: Object.freeze({ label: 'Discard', action: 'discardAndProceed' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  routeChange: Object.freeze({
    title: () => 'Save draft before leaving?',
    summary: () => '1 unsaved draft.',
    primary: Object.freeze({ label: 'Save and leave', action: 'saveAndProceed' }),
    secondary: Object.freeze({ label: 'Discard', action: 'discardAndProceed' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  forgetDevice: Object.freeze({
    title: (ctx = {}) => 'Forget ' + (ctx.deviceName || 'this device') + '?',
    summary: () => 'The next pick in the device list will pair fresh. Active session data is unaffected.',
    primary: Object.freeze({ label: 'Forget', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  clearLocalDraft: Object.freeze({
    title: () => 'Clear local draft?',
    summary: (ctx = {}) => {
      const count = ctx.itemCount ?? 1;
      return 'Removes ' + count + ' unsaved local ' + (count === 1 ? 'annotation' : 'annotations') + ' from this browser.';
    },
    primary: Object.freeze({ label: 'Clear local draft', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  clearServerDrafts: Object.freeze({
    title: () => 'Delete saved draft feedback?',
    summary: (ctx = {}) => {
      const count = ctx.itemCount ?? 0;
      return 'Deletes ' + count + ' saved draft ' + (count === 1 ? 'item' : 'items') + ' from this session.';
    },
    primary: Object.freeze({ label: 'Delete drafts', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  fingerprintMismatch: Object.freeze({
    title: () => 'Screen changed since capture?',
    summary: () => 'Keep the frozen preview only if the annotation still describes that frame.',
    primary: Object.freeze({ label: 'Recapture', action: 'recapture' }),
    secondary: Object.freeze({ label: 'Force save', action: 'force' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  recaptureRecoveredDraft: Object.freeze({
    title: () => 'Recapture recovered draft?',
    summary: () => 'Pins will be remapped onto the latest app screen.',
    primary: Object.freeze({ label: 'Recapture', action: 'confirm' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  staleDraftConflict: Object.freeze({
    title: () => 'A newer save advanced the draft.',
    summary: () => "The server's draft moved ahead since your last sync. Pick whose version wins.",
    primary: Object.freeze({ label: 'Keep mine (overwrite)', action: 'overwrite' }),
    secondary: Object.freeze({ label: "Use server's version", action: 'useServer' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
  pendingRecovery: Object.freeze({
    title: () => 'Recover unsaved draft?',
    summary: (ctx = {}) => {
      const count = ctx.itemCount ?? 1;
      const label = count === 1 ? 'annotation' : 'annotations';
      return count + ' ' + label + ' preserved from previous session.';
    },
    primary: (ctx = {}) => ctx.canResume === false ? null : Object.freeze({ label: 'Resume draft', action: 'resume' }),
    secondary: Object.freeze({ label: 'Recapture', action: 'recapture' }),
    tertiary: Object.freeze({ label: 'Discard', action: 'discard' }),
    cancel: Object.freeze({ label: 'Cancel', action: 'cancel' }),
  }),
});

function resolveBoundarySlot(slot, ctx) {
  if (!slot) return null;
  return typeof slot === 'function' ? slot(ctx) : slot;
}

function boundarySlotClass(slot) {
  if (!slot || slot.action === 'cancel') return '';
  if (slot.action === 'discard' || slot.action === 'discardAndProceed') return 'annotation-danger';
  return 'annotation-done';
}

function renderBoundaryDialog(variantName, ctx = {}) {
  const variant = BoundaryDialogVariants[variantName];
  if (!variant) throw new Error('Unknown boundary variant: ' + variantName);

  const root = document.getElementById('sessionBoundarySheet');
  if (!root) return;
  if (!root.dataset) root.dataset = {};
  root.dataset.boundaryVariant = variantName;

  root.querySelector('[data-boundary-title]').textContent = variant.title(ctx);
  root.querySelector('[data-boundary-summary]').textContent = variant.summary(ctx);

  const slots = [variant.cancel, variant.tertiary || null, variant.secondary, variant.primary]
    .map((slot) => resolveBoundarySlot(slot, ctx));
  const buttons = Array.from(root.querySelectorAll('.session-boundary-actions [data-boundary-action]'));
  const primaryIndex = slots.length - 1;
  buttons.forEach((button, index) => {
    const slot = slots[index];
    delete button.dataset.boundaryPrimary;
    if (!slot) {
      button.hidden = true;
      button.textContent = '';
      // Keep the bare `data-boundary-action` attribute so the next render's
      // querySelectorAll('[data-boundary-action]') still finds this button.
      // The click handler treats a falsy action value as "ignore".
      button.dataset.boundaryAction = '';
      return;
    }
    button.hidden = false;
    button.textContent = slot.label;
    button.dataset.boundaryAction = slot.action;
    button.className = boundarySlotClass(slot);
    if (index === primaryIndex && slot.action !== 'cancel') {
      button.dataset.boundaryPrimary = '';
    }
  });
  if (typeof statusSurfaceRegistry !== 'undefined') {
    statusSurfaceRegistry.show('sessionBoundarySheet', {
      surfaceClass: 'modal',
      priority: 1,
      element: root,
    });
  } else {
    root.hidden = false;
  }
}
