// @requires state.js
// availability.js
function computeBlockedReason(status, isAnnotateMode) {
  if (!status) return null;
  if (status.screenInteractive === false) return 'screenOff';
  if (status.keyguardLocked === true) return 'locked';
  if (status.appForeground === false) return 'background';
  if (status.pictureInPicture === true) return 'pictureInPicture';
  if (status.unresponsive === true) return 'unresponsive';
  if (isAnnotateMode && (status.rootsCount === 0)) return 'noComposeUi';
  return null;
}

function createBlockedReasonDebouncer({ delayMs = 300, now = () => Date.now() } = {}) {
  let committed = null;
  let pending = null;
  let pendingSince = 0;
  return {
    observe(reason) {
      if (reason === null) {
        pending = null;
        pendingSince = 0;
        committed = null;
        return null;
      }
      if (reason === committed) return committed;
      if (pending !== reason) {
        pending = reason;
        pendingSince = now();
        return committed;
      }
      if (now() - pendingSince >= delayMs) {
        committed = reason;
        pending = null;
        pendingSince = 0;
        return committed;
      }
      return committed;
    }
  };
}

const UNRESPONSIVE_BACKOFF_MS = [1000, 2000, 5000, 10000, 30000];

function createUnresponsiveTracker({ threshold = 3 } = {}) {
  let streak = 0;
  return {
    observeFailure() {
      streak += 1;
      return streak >= threshold;
    },
    observeSuccess() {
      streak = 0;
    },
    isUnresponsive() {
      return streak >= threshold;
    },
    nextBackoffMs() {
      const idx = Math.min(streak, UNRESPONSIVE_BACKOFF_MS.length - 1);
      return UNRESPONSIVE_BACKOFF_MS[idx];
    },
  };
}
