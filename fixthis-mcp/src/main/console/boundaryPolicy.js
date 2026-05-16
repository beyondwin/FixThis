// @requires boundaryTriggers.js
// boundaryPolicy.js - pure resolver for the context transition matrix.

function boundaryPolicy(trigger, state, _payload) {
  if (state === 'none' || state === 'saved') return 'pass';

  switch (trigger) {
    case Trigger.SESSION_SWITCH:
    case Trigger.SESSION_CREATE:
    case Trigger.SESSION_DELETE:
    case Trigger.ROUTE_CHANGE:
    case Trigger.EDITOR_BACK:
      return state === 'draft' ? 'boundaryDialog' : 'discardWithToast';

    case Trigger.SCREEN_SWITCH:
    case Trigger.NEW_CAPTURE:
      return state === 'draft' ? 'preserve' : 'discardWithToast';

    case Trigger.ELEMENT_CLICK:
      return state === 'draft' ? 'preserve' : 'silentSwap';

    case Trigger.ESCAPE_KEY:
      return state === 'draft' ? 'discardConfirm' : 'discardWithToast';

    case Trigger.BROWSER_REFRESH:
    case Trigger.TAB_CLOSE:
      return state === 'draft' ? 'beforeunloadGuard' : 'silentLoss';

    case Trigger.SERVER_DISCONNECT:
      return 'retainWithBadge';

    case Trigger.RECONNECT:
      return 'staleRevalidate';

    case Trigger.BRIDGE_MISMATCH:
      return state === 'draft' ? 'retainAndMigrate' : 'discardWithToast';

    case Trigger.ACTIVITY_DRIFT:
    case Trigger.INACTIVITY:
      return 'retain';

    default:
      return 'pass';
  }
}
