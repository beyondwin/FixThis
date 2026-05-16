# Manual Test Checklist - Console Draft Lifecycle and Affordance Clarity

Run these by hand before declaring the plan done.

1. **Pending discard on session switch.** Open the console, click an element on
   the canvas, do not type a comment, then click another session. Expect the
   empty selection to discard with a toast. Reload the page and expect no
   recovery prompt.
2. **Draft beforeunload and recovery.** Click an element, type one character,
   then refresh the browser. Expect the native browser warning. Confirm leave,
   reload, and expect the draft footer plus the typed character restored.
3. **Disconnect single surface.** Unplug the device or stop the bridge.
   Expect only `canvasBlockedOverlay` plus the topbar pill change. No stacked
   banner, lock bar, badge, and stale notice. If a draft existed, also expect
   the `stalenessBanner` text `1 unsaved draft preserved locally`.
4. **Reconnect.** Plug the device back in. Expect the overlay to dismiss and a
   single `Connection restored` badge to appear briefly. Other surfaces should
   resume their normal behavior.
5. **Back navigation per state.** From the annotation editor, pending state
   should go back to the list with a toast, draft state should open the save /
   discard / cancel boundary dialog, and clean saved state should go back to
   the list immediately.

If any of these fail, the plan is not done.
