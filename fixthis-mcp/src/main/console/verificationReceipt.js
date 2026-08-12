// @requires state.js

function receiptForItem(session, item) {
  if (!item?.itemId) return null;
  const receipts = (session?.verificationReceipts || [])
    .filter(receipt => receipt?.itemId === item.itemId)
    .sort((left, right) =>
      ((Number(right?.createdAtEpochMillis) || 0) - (Number(left?.createdAtEpochMillis) || 0)) ||
      String(right?.receiptId || '').localeCompare(String(left?.receiptId || ''))
    );
  if (item.status === 'resolved' && item.resolutionVerificationReceiptId) {
    return receipts.find(receipt => receipt.receiptId === item.resolutionVerificationReceiptId) || null;
  }
  return receipts[0] || null;
}

function verificationBadgeModel(session, item) {
  const receipt = receiptForItem(session, item);
  if (item?.status === 'resolved' && receipt?.verdict === 'pass') {
    return { tone: 'success', label: 'verified' };
  }
  if (item?.status === 'resolved' && receipt?.verdict === 'warn') {
    return { tone: 'warning', label: 'warning' };
  }
  if (item?.status !== 'resolved' && receipt?.verdict === 'fail') {
    return { tone: 'danger', label: 'verification failed' };
  }
  if (item?.status === 'resolved' && !item.resolutionVerificationReceiptId) {
    return { tone: 'neutral', label: 'unverified' };
  }
  return null;
}

function verificationBadgeHtml(session, item) {
  const model = verificationBadgeModel(session, item);
  if (!model) return '';
  const label = escapeHtml(model.label);
  return '<span class="verification-badge" data-tone="' + escapeHtml(model.tone) +
    '" aria-label="Verification: ' + label + '">' + label + '</span>';
}

function verificationTokenLabel(value) {
  return String(value ?? '')
    .replaceAll('_', ' ')
    .trim() || '-';
}

function verificationVerdictModel(verdict) {
  if (verdict === 'pass') return { tone: 'success', label: 'PASS' };
  if (verdict === 'warn') return { tone: 'warning', label: 'REVIEWED WITH WARNING' };
  if (verdict === 'fail') return { tone: 'danger', label: 'FAIL' };
  return { tone: 'neutral', label: verificationTokenLabel(verdict) };
}

function verificationOutcomeTone(outcome) {
  if (outcome === 'passed') return 'success';
  if (outcome === 'warning') return 'warning';
  if (outcome === 'failed') return 'danger';
  return 'neutral';
}

function verificationChecksHtml(receipt) {
  const checks = Array.isArray(receipt?.checks) ? receipt.checks : [];
  if (!checks.length) return '<p class="verification-empty">No checks recorded.</p>';
  return '<ul class="verification-list verification-check-list">' + checks.map(check => {
    const tone = verificationOutcomeTone(check?.outcome);
    return '<li data-tone="' + escapeHtml(tone) + '">' +
      '<div class="verification-entry-heading">' +
        '<strong dir="auto">' + escapeHtml(verificationTokenLabel(check?.kind)) + '</strong>' +
        '<span dir="auto">' + escapeHtml(verificationTokenLabel(check?.outcome)) + '</span>' +
      '</div>' +
      '<p class="verification-entry-message" dir="auto">' + escapeHtml(check?.message) + '</p>' +
    '</li>';
  }).join('') + '</ul>';
}

function verificationAssertionsHtml(receipt) {
  const assertions = Array.isArray(receipt?.assertions) ? receipt.assertions : [];
  if (!assertions.length) return '<p class="verification-empty">No explicit assertions.</p>';
  return '<ul class="verification-list verification-assertion-list">' + assertions.map(assertion => {
    const details = [];
    if (assertion?.value != null) {
      details.push('<span><b>Value</b> <span dir="auto">' + escapeHtml(assertion.value) + '</span></span>');
    }
    if (assertion?.role != null) {
      details.push('<span><b>Role</b> <span dir="auto">' + escapeHtml(assertion.role) + '</span></span>');
    }
    return '<li>' +
      '<strong dir="auto">' + escapeHtml(verificationTokenLabel(assertion?.kind)) + '</strong>' +
      (details.length ? '<div class="verification-assertion-details">' + details.join('') + '</div>' : '') +
    '</li>';
  }).join('') + '</ul>';
}

function verificationBaselineScreenshotUrl(session, receipt) {
  const screen = (session?.screens || []).find(candidate => candidate?.screenId === receipt?.baselineScreenId);
  if (!screen?.screenshot?.desktopFullPath) return null;
  return '/api/screens/' + encodeURIComponent(String(receipt.baselineScreenId)) +
    '/screenshot/full?sessionId=' + encodeURIComponent(String(session.sessionId));
}

function verificationAfterScreenshotUrl(session, receipt) {
  if (!receipt?.afterScreenshot?.desktopFullPath || !session?.sessionId) return null;
  return '/api/verification-receipts/' + encodeURIComponent(String(receipt.receiptId)) +
    '/screenshot/after?sessionId=' + encodeURIComponent(String(session.sessionId));
}

function verificationScreenshotsHtml(session, receipt) {
  const baselineUrl = verificationBaselineScreenshotUrl(session, receipt);
  const afterUrl = verificationAfterScreenshotUrl(session, receipt);
  if (!baselineUrl && !afterUrl) return '';
  const figure = (url, caption, alt, ariaLabel) => '<figure class="verification-screenshot">' +
    '<a href="' + escapeHtml(url) + '" target="_blank" rel="noopener" aria-label="' + escapeHtml(ariaLabel) + '">' +
      '<img src="' + escapeHtml(url) + '" alt="' + escapeHtml(alt) + '" loading="lazy" decoding="async">' +
    '</a>' +
    '<figcaption>' + escapeHtml(caption) + '</figcaption>' +
  '</figure>';
  return '<div class="verification-screenshots" aria-label="Verification screenshots">' +
    (baselineUrl ? figure(
      baselineUrl,
      'Before',
      'Baseline screenshot captured before the change',
      'Open baseline screenshot',
    ) : '') +
    (afterUrl ? figure(
      afterUrl,
      'After',
      'Screenshot captured for this verification receipt',
      'Open verification screenshot',
    ) : '') +
  '</div>';
}

function verificationReceiptSectionHtml(session, item) {
  const receipt = receiptForItem(session, item);
  if (!receipt) return '';
  const verdict = verificationVerdictModel(receipt.verdict);
  const capturedAt = formatTime(receipt.createdAtEpochMillis);
  return '<section class="annotation-section verification-receipt-section" aria-label="Verification receipt details">' +
    '<div class="verification-receipt-heading">' +
      '<h3>Verification receipt</h3>' +
      '<span class="verification-badge" data-tone="' + escapeHtml(verdict.tone) + '">' +
        escapeHtml(verdict.label) +
      '</span>' +
    '</div>' +
    '<dl class="verification-meta">' +
      '<div><dt>Receipt</dt><dd dir="auto">' + escapeHtml(receipt.receiptId) + '</dd></div>' +
      '<div><dt>Captured</dt><dd dir="auto">' + escapeHtml(capturedAt) + '</dd></div>' +
    '</dl>' +
    '<div class="verification-subsection"><h4>Checks</h4>' + verificationChecksHtml(receipt) + '</div>' +
    '<div class="verification-subsection"><h4>Assertions</h4>' + verificationAssertionsHtml(receipt) + '</div>' +
    verificationScreenshotsHtml(session, receipt) +
  '</section>';
}
