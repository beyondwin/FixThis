// @requires state.js, draftUseCases.js, boundaryDialogVariants.js
// pendingRecoveryUi.js - boundary sheet and banner UI for recovered local drafts.

function hideBoundaryDialog(variantName = null) {
  const sheet = document.getElementById('sessionBoundarySheet');
  if (!sheet) return;
  if (variantName && sheet.dataset.boundaryVariant !== variantName) return;
  statusSurfaceRegistry.hide('sessionBoundarySheet');
  delete sheet.dataset.boundaryVariant;
}

function ensurePendingRecoveryBanner() {
  let banner = document.getElementById('pendingRecoveryBanner');
  if (banner) return banner;
  banner = document.createElement('div');
  banner.id = 'pendingRecoveryBanner';
  banner.setAttribute('data-testid', 'pending-recovery-banner');
  banner.className = 'annotation-banner annotation-banner-warn pending-recovery-banner';
  banner.setAttribute('role', 'status');
  banner.setAttribute('aria-live', 'polite');
  const parent = pendingItems?.parentElement || inspectorBody || document.body;
  if (pendingItems && pendingItems.parentElement === parent) parent.insertBefore(banner, pendingItems);
  else parent.prepend(banner);
  return banner;
}

function renderPendingRecoveryBanner() {
  const banner = ensurePendingRecoveryBanner();
  const summary = draftRecoverySummary(pendingRecovery);
  if (!pendingRecovery || !summary.total || draftFlow() || draftItemList().length) {
    banner.hidden = true;
    hideBoundaryDialog('pendingRecovery');
    return;
  }
  const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session);
  const canResume = ownership.canResume && hasRecoverablePreviewContext(pendingRecovery);
  const canRecapture = ownership.canRecapture;
  const summaryParts = [];
  if (summary.commented) summaryParts.push(countLabel(summary.commented, 'draft comment', 'draft comments'));
  if (summary.pinOnly) summaryParts.push(countLabel(summary.pinOnly, 'pin without comment', 'pins without comments'));
  const detail = ownership.mode === 'deleted'
    ? 'This local draft belongs to a deleted session. Discard it to continue.'
    : ownership.mode === 'closed'
      ? 'This local draft belongs to a closed session. Recapture into an active session to continue.'
      : canResume
        ? 'Resume the local draft for this session.'
        : 'Recapture the current app screen to continue this local draft.';
  banner.hidden = false;
  banner.innerHTML =
    '<div class="pending-recovery-copy" data-pending-recovery-copy>' +
      '<strong>' + escapeHtml(summaryParts.join(' · ')) + '</strong>' +
      '<div>' + escapeHtml(detail) + '</div>' +
    '</div>';
  renderBoundaryDialog('pendingRecovery', { canResume, canRecapture, itemCount: summary.total });
}

function handlePendingRecoveryBoundaryAction(action) {
  if (action === 'cancel') {
    hideBoundaryDialog('pendingRecovery');
    return;
  }
  if (action === 'resume') {
    if (!hasRecoverablePreviewContext(pendingRecovery)) return;
    restorePendingRecoveryContext(pendingRecovery);
    pendingRecovery = null;
    renderPendingRecoveryBanner();
    render();
    return;
  }
  if (action === 'recapture') {
    const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session);
    if (!ownership.canRecapture) return;
    hideBoundaryDialog('pendingRecovery');
    recapturePendingRecovery().catch(showError);
    return;
  }
  if (action === 'discard') {
    if (pendingRecovery?.schemaVersion === 2) {
      createBrowserDraftPorts().storage.deleteWorkspace(
        pendingRecovery.sessionId || pendingRecovery.context?.sessionId,
        pendingRecovery.workspaceId,
      );
    }
    pendingRecovery = null;
    renderPendingRecoveryBanner();
  }
}
