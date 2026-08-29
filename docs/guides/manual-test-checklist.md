# Manual Test Checklist

Run these by hand before calling a console draft-lifecycle change done.

1. **Pending discard on session switch.** Click an element, do not type, click
   another session. Empty selection should discard with a toast. Reload: no
   recovery prompt.
2. **Draft beforeunload and recovery.** Click an element, type one character,
   refresh. Expect the native warning. Leave, reload: draft footer and the
   typed character restore.
3. **Disconnect single surface.** Unplug the device or stop the bridge. Expect
   only `canvasBlockedOverlay` plus the top-bar pill. No stacked banner, lock
   bar, badge, and stale notice. If a draft existed, also expect
   `stalenessBanner` text `1 unsaved draft preserved locally`.
4. **Reconnect.** Plug the device back in. Overlay dismisses. One
   `Connection restored` badge. Other surfaces resume.
5. **Back navigation per state.** Annotation editor: pending goes back to the
   list with a toast; draft opens save / discard / cancel; clean saved goes
   back immediately.

If any of these fail, the change is not done.
