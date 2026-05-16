// @requires (none)
// boundaryTriggers.js - frozen enum of context-transition triggers.

const Trigger = Object.freeze({
  SESSION_SWITCH: 'sessionSwitch',
  SESSION_CREATE: 'sessionCreate',
  SESSION_DELETE: 'sessionDelete',
  SCREEN_SWITCH: 'screenSwitch',
  NEW_CAPTURE: 'newCapture',
  ELEMENT_CLICK: 'elementClick',
  ESCAPE_KEY: 'escapeKey',
  BROWSER_REFRESH: 'browserRefresh',
  TAB_CLOSE: 'tabClose',
  ROUTE_CHANGE: 'routeChange',
  SERVER_DISCONNECT: 'serverDisconnect',
  RECONNECT: 'reconnect',
  BRIDGE_MISMATCH: 'bridgeMismatch',
  ACTIVITY_DRIFT: 'activityDrift',
  INACTIVITY: 'inactivity',
  EDITOR_BACK: 'editorBack',
});
