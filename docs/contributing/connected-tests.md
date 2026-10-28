# Connected (instrumented) tests

The connected workflow schedules `connectedDebugAndroidTest` nightly, not
per PR. GitHub only runs that schedule while the workflow is enabled.

## Local Robolectric regressions

Run host-side Android regressions with:

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon
```

`OrientationCaptureTest` runs the full snapshot capture on a separate
instrumentation test thread. Activity setup and teardown stay on the main
thread, leaving that looper free to deliver PixelCopy callbacks. It checks
orientation, display metrics, screenshot persistence, and the cached snapshot.
Blocking the Robolectric main thread with `runBlocking` can deadlock those
callbacks; increasing a timeout does not fix the scheduling problem.

`BridgeServerConcurrencyTest` exercises accept, length-prefixed framing,
request dispatch, and cancellation through the real server. Test-only socket
shadows adapt local sockets unsupported by Robolectric to JVM loopback sockets.
The test holds handler cleanup behind an explicit signal and proves that
`stop()` waits for cleanup before returning. This host regression does not
claim to test Android's native AF_UNIX implementation.

The local product-path gate is `npm run android:proof -- --strict`. It needs
an unlocked device or emulator and exercises the bridge, browser, MCP, and
external fixtures. It is separate from `connectedDebugAndroidTest` and from
the two focused Robolectric regressions above.

## Why nightly-only?

1. **Cost** — emulator boot plus the instrumented suite is 15–30 minutes.
2. **Flake risk** — emulator boots, ADB, lockscreens, and animation timing
   fail for reasons unrelated to the change.

Workflow: `.github/workflows/connected-tests.yml` at 04:00 UTC and on
`workflow_dispatch`. Not on `pull_request`. The test step is
`continue-on-error: true`.

Promotion to a required PR check needs **14 consecutive green nightly runs**
plus flake triage below.

```bash
npm run checks:observation -- --require-ready connected-tests
```

Do not remove `continue-on-error` or add PR branch protection until that
passes and the latest flakes are triaged.

## Flake triage process

1. **First failure** — note it. Do not act yet.
2. **Second failure within a week** — treat as flaky. Open an issue, add a
   row to **Disabled tests**, disable with `@Ignore("flaky — see #<issue>")`.
3. **Investigate within one sprint.**
4. **Re-enable** after the flake is fixed. Remove `@Ignore` and the table row.

## Disabled tests

| Test | Disabled since | Owner | Re-enable when |
| ---- | -------------- | ----- | -------------- |

_(Empty — no tests are currently disabled for flake.)_
