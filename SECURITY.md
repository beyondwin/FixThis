# Security

FixThis is a local-first debug tool for Android Jetpack Compose apps.

## Supported Scope

- Debug builds only.
- Local desktop MCP process.
- Localhost browser feedback console.
- ADB access to the developer's own device or emulator.
- Local feedback session artifacts under `.fixthis/`.
- Redacted, quota-limited runtime diagnostics under
  `.fixthis/runtime-evidence/`.

## Local Debug Guards

- The Android sidekick bridge uses an app-private session token read through `adb run-as`; bridge requests with a missing or mismatched token are rejected.
- The browser console is bound to loopback by default.
- Mutating console `/api/*` requests require `X-FixThis-Console-Token`, generated per console server and injected into the served local HTML.
- Mutating console requests with unexpected non-localhost `Origin` headers are rejected.

These guards reduce accidental local cross-origin mutation risk. They are not a replacement for production authentication or remote hosting controls.

## Not Supported

- Production feedback collection.
- Remote console hosting.
- Inspecting other apps.
- Sharing `.fixthis/feedback-sessions/` artifacts without reviewing their screenshots and comments.
- Uploading screenshots, source hints, feedback, or handoff data by default.

## Threat Model

For the full picture — assets, trust boundaries, in-scope and out-of-scope
adversaries, current mitigations, and known open gaps — see
[`docs/reference/threat-model.md`](docs/reference/threat-model.md).

## Reporting

Report security issues privately to the project owner before publishing details.
The [threat model](docs/reference/threat-model.md) is a useful reference when
deciding whether a finding is in scope.
