// @requires state.js
// activityDrift.js — SIF-6: pure decision helper that decides whether the
// currently foregrounded activity differs from the activity captured when
// the draft freeze was taken. Returning drift=true tells the caller
// to surface the inline warning + "Start new freeze" button on the
// next pin form.
//
// Bare-function module — concatenated into resources/console/app.js by
// scripts/build-console-assets.mjs. Must be registered BEFORE annotations.js
// (its sole caller) in the sources array.

function checkActivityDrift(flow, current) {
  const expected = flow && flow.activity ? flow.activity : null;
  const actual = current && current.activity ? current.activity : null;
  if (!expected || !actual) {
    return { drift: false };
  }
  if (expected === actual) {
    return { drift: false };
  }
  return { drift: true, expected: expected, actual: actual };
}
