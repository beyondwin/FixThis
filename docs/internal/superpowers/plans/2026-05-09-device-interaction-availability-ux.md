# Device Interaction Availability UX — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the misleading "No component found" error and missing auto-resume in the FixThis web console with a single `interactionBlockedReason` sub-state of *Connected* that covers screen-off, app-backgrounded, lock screen, Picture-in-Picture, unresponsive sample app, and "no Compose UI on this screen", and automatically restores the user's last toolMode/frozen preview/pending pins when the cause clears.

**Architecture:** The Android sidekick exposes raw availability signals (`screenInteractive`, `keyguardLocked`, `appForeground`, `pictureInPicture`) on `BridgeStatus`. The desktop MCP adds these plus `rootsCount` to its `ConsoleConnectionStatus` payload. The browser console computes a single derived `interactionBlockedReason` via a pure module `availability.js`, gates canvas input, renders an overlay, and preserves session state across transitions. Sidekick changes are non-breaking (nullable fields with kotlinx.serialization defaults), so an older sidekick falls back to today's behavior.

**Tech Stack:** Kotlin 1.9 + kotlinx.serialization (sidekick + MCP); JUnit 4 + Robolectric (sidekick tests); JUnit 5 + Ktor test (MCP tests); vanilla ES2020 JS, vanilla CSS (console); Node 20 built-in `node --test` runner (new pure JS tests); Playwright (existing browser smoke).

---

## Companion design

See `docs/superpowers/specs/2026-05-09-device-interaction-availability-ux-design.md` for the full problem statement, priority order rationale, message catalog, compatibility analysis, risks, and acceptance criteria.

## Conventions

- **Bundling:** Source-of-truth JS lives in `fixthis-mcp/src/main/console/`. Never hand-edit `fixthis-mcp/src/main/resources/console/app.js` — it is generated. After every JS source edit run:
  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```
  The first command regenerates `app.js`; the second exits 0 silently when in sync, non-zero with `Generated console app.js is out of date.` when drifted. Each task's bundler step restates this expectation as "silent success (exit 0)".
- **Sidekick test runner:** `./gradlew :fixthis-compose-sidekick:testDebugUnitTest`
- **MCP test runner:** `./gradlew :fixthis-mcp:test`
- **All Kotlin unit suites in one shot:**
  ```bash
  ./gradlew :fixthis-compose-core:test \
            :fixthis-cli:test \
            :fixthis-mcp:test \
            :fixthis-compose-sidekick:testDebugUnitTest \
            :fixthis-gradle-plugin:test
  ```
- **JS pure tests:** `node --test scripts/console-availability-test.mjs`
- **JS browser smoke:** `npm run console:smoke`
- **JS syntax check after bundle:** `node --check fixthis-mcp/src/main/resources/console/app.js`
- **Commit style:** lowercase scope, imperative mood, body optional. Each task ends with a single commit.

## File map

**Created:**
- `fixthis-mcp/src/main/console/availability.js` — pure module: `computeBlockedReason`, `createBlockedReasonDebouncer`, `unresponsiveTracker`
- `scripts/console-availability-test.mjs` — Node `node --test` for the pure module
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacksTest.kt` — counter + lastResumed weak ref
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt` — JSON round-trip + signal wiring (Robolectric)

**Modified:**
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` — `BridgeStatus` data class
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacks.kt` — counter + last-resumed weak ref
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/FixThisBridgeRuntime.kt` — populate availability fields in `BridgeEnvironment.status()`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt` — add `availability` payload
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt` — query `bridge.status()` on READY and surface fields
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` — extend connection-status assertions
- `fixthis-mcp/src/main/console/state.js` — new sub-state shape
- `fixthis-mcp/src/main/console/connection.js` — call availability tracker, integrate failure streak, drive backoff
- `fixthis-mcp/src/main/console/annotations.js` — gate click/drag, suppress "No component found"
- `fixthis-mcp/src/main/console/preview.js` — overlay render hook
- `fixthis-mcp/src/main/console/devices.js` — chip suffix
- `fixthis-mcp/src/main/resources/console/index.html` — overlay container
- `fixthis-mcp/src/main/resources/console/styles.css` — overlay styles
- `scripts/console-browser-smoke.mjs` — fixture additions for blocked → unblocked flow
- `package.json` — `console:availability:test` script
- `CHANGELOG.md` — entry

---

## Task 1 — Sidekick: add nullable availability fields to `BridgeStatus`

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt:206-229`
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt`

**Goal:** Extend the `BridgeStatus` data class with four nullable Booleans. Keep the legacy 5-arg constructor for source compatibility. Verify JSON round-trip (encode + decode) preserves new fields and round-trips an old payload (without the fields) into all-null defaults.

- [ ] **Step 1: Write the failing test**

  Create `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.compose.sidekick.bridge

  import kotlinx.serialization.json.Json
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class BridgeStatusAvailabilityTest {
      private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

      @Test
      fun `legacy constructor populates new fields with null`() {
          val status = BridgeStatus(
              activity = "MainActivity",
              rootsCount = 1,
              sidekickVersion = "1",
              bridgeProtocolVersion = "1",
              sourceIndexAvailable = true,
          )
          assertNull(status.screenInteractive)
          assertNull(status.keyguardLocked)
          assertNull(status.appForeground)
          assertNull(status.pictureInPicture)
      }

      @Test
      fun `serializing populated status emits availability fields`() {
          val status = BridgeStatus(
              activity = "MainActivity",
              rootsCount = 2,
              sidekickVersion = "1",
              bridgeProtocolVersion = "1",
              sourceIndexAvailable = true,
              capabilities = BridgeCapabilities(),
              screenInteractive = true,
              keyguardLocked = false,
              appForeground = true,
              pictureInPicture = false,
          )
          val text = json.encodeToString(BridgeStatus.serializer(), status)
          assertTrue(text.contains("\"screenInteractive\":true"))
          assertTrue(text.contains("\"keyguardLocked\":false"))
          assertTrue(text.contains("\"appForeground\":true"))
          assertTrue(text.contains("\"pictureInPicture\":false"))
      }

      @Test
      fun `deserializing legacy payload yields null availability fields`() {
          val legacy = """
              {
                "activity": "MainActivity",
                "rootsCount": 1,
                "sidekickVersion": "1",
                "bridgeProtocolVersion": "1",
                "sourceIndexAvailable": true
              }
          """.trimIndent()
          val status = json.decodeFromString(BridgeStatus.serializer(), legacy)
          assertEquals("MainActivity", status.activity)
          assertNull(status.screenInteractive)
          assertNull(status.keyguardLocked)
          assertNull(status.appForeground)
          assertNull(status.pictureInPicture)
      }
  }
  ```

- [ ] **Step 2: Run test, verify it fails**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "*BridgeStatusAvailabilityTest*"
  ```
  Expected: compile failure on `screenInteractive`/`keyguardLocked`/`appForeground`/`pictureInPicture` (unresolved properties).

- [ ] **Step 3: Implement the data class change**

  In `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` replace the existing `BridgeStatus` data class (currently lines 206-229) with:

  ```kotlin
  @Serializable
  data class BridgeStatus(
      val activity: String? = null,
      val rootsCount: Int,
      val sidekickVersion: String,
      val bridgeProtocolVersion: String,
      val sourceIndexAvailable: Boolean,
      val capabilities: BridgeCapabilities = BridgeCapabilities(),
      val screenInteractive: Boolean? = null,
      val keyguardLocked: Boolean? = null,
      val appForeground: Boolean? = null,
      val pictureInPicture: Boolean? = null,
  ) {
      constructor(
          activity: String?,
          rootsCount: Int,
          sidekickVersion: String,
          bridgeProtocolVersion: String,
          sourceIndexAvailable: Boolean,
      ) : this(
          activity = activity,
          rootsCount = rootsCount,
          sidekickVersion = sidekickVersion,
          bridgeProtocolVersion = bridgeProtocolVersion,
          sourceIndexAvailable = sourceIndexAvailable,
          capabilities = BridgeCapabilities(),
          screenInteractive = null,
          keyguardLocked = null,
          appForeground = null,
          pictureInPicture = null,
      )
  }
  ```

- [ ] **Step 4: Run test, verify it passes**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "*BridgeStatusAvailabilityTest*"
  ```
  Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
          fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt
  git commit -m "feat(sidekick): add nullable availability fields to BridgeStatus"
  ```

---

## Task 2 — Sidekick: foreground + PiP tracking in lifecycle callbacks

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacks.kt`
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacksTest.kt`

**Goal:** Track a resumed-activity counter (atomic int) and a `WeakReference<Activity>` to the most recent resumed activity. Expose `isAppForeground()` and `lastResumedActivity()` for the runtime to read.

- [ ] **Step 1: Write the failing test**

  Create `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacksTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.compose.sidekick.lifecycle

  import android.app.Activity
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertNull
  import org.junit.Assert.assertSame
  import org.junit.Assert.assertTrue
  import org.junit.Test
  import org.mockito.Mockito.mock

  class FixThisActivityLifecycleCallbacksTest {
      private val callbacks = FixThisActivityLifecycleCallbacks()

      @Test
      fun `app is not foreground until activity resumed`() {
          assertFalse(callbacks.isAppForeground())
          assertNull(callbacks.lastResumedActivity())
      }

      @Test
      fun `resume increments counter and stores last activity`() {
          val activity = mock(Activity::class.java)
          callbacks.onActivityResumed(activity)
          assertTrue(callbacks.isAppForeground())
          assertSame(activity, callbacks.lastResumedActivity())
      }

      @Test
      fun `pause decrements counter`() {
          val activity = mock(Activity::class.java)
          callbacks.onActivityResumed(activity)
          callbacks.onActivityPaused(activity)
          assertFalse(callbacks.isAppForeground())
      }

      @Test
      fun `multiple activities track counter`() {
          val a1 = mock(Activity::class.java)
          val a2 = mock(Activity::class.java)
          callbacks.onActivityResumed(a1)
          callbacks.onActivityResumed(a2)
          assertTrue(callbacks.isAppForeground())
          assertSame(a2, callbacks.lastResumedActivity())
          callbacks.onActivityPaused(a2)
          assertTrue(callbacks.isAppForeground()) // a1 still resumed
          callbacks.onActivityPaused(a1)
          assertFalse(callbacks.isAppForeground())
      }
  }
  ```

- [ ] **Step 2: Run test, verify it fails**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "*FixThisActivityLifecycleCallbacksTest*"
  ```
  Expected: compile failure on `isAppForeground` and `lastResumedActivity` (unresolved references).

- [ ] **Step 3: Implement counter + weak ref**

  Replace the current contents of `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacks.kt` with:

  ```kotlin
  package io.beyondwin.fixthis.compose.sidekick.lifecycle

  import android.app.Activity
  import android.app.Application
  import android.os.Bundle
  import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime
  import io.beyondwin.fixthis.compose.sidekick.overlay.FixThisConnectionStatusHostLayout
  import java.lang.ref.WeakReference
  import java.util.concurrent.atomic.AtomicInteger

  class FixThisActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
      private val resumedCounter = AtomicInteger(0)
      @Volatile private var lastResumed: WeakReference<Activity>? = null

      fun isAppForeground(): Boolean = resumedCounter.get() > 0

      fun lastResumedActivity(): Activity? = lastResumed?.get()

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

      override fun onActivityStarted(activity: Activity) = Unit

      override fun onActivityResumed(activity: Activity) {
          resumedCounter.incrementAndGet()
          lastResumed = WeakReference(activity)
          FixThisConnectionStatusHostLayout.attachTo(activity)
          FixThisBridgeRuntime.onActivityResumed(activity)
      }

      override fun onActivityPaused(activity: Activity) {
          resumedCounter.updateAndGet { current -> if (current > 0) current - 1 else 0 }
          FixThisConnectionStatusHostLayout.detachFrom(activity)
      }

      override fun onActivityStopped(activity: Activity) = Unit

      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

      override fun onActivityDestroyed(activity: Activity) {
          FixThisConnectionStatusHostLayout.detachFrom(activity)
          FixThisBridgeRuntime.onActivityDestroyed(activity)
      }
  }
  ```

- [ ] **Step 4: Run test, verify it passes**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "*FixThisActivityLifecycleCallbacksTest*"
  ```
  Expected: 4 tests passed.

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacks.kt \
          fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/lifecycle/FixThisActivityLifecycleCallbacksTest.kt
  git commit -m "feat(sidekick): track resumed activity counter and last-resumed weak ref"
  ```

---

## Task 3 — Sidekick: populate availability signals in `status()`

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` (the `BridgeEnvironment` interface and its `FixThisBridgeRuntime` impl around line 354)
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThis.kt` (init wiring — pass the lifecycle callbacks instance into `FixThisBridgeRuntime`)

**Goal:** On every `status()` call, evaluate `PowerManager.isInteractive`, `KeyguardManager.isKeyguardLocked`, the lifecycle counter, and `Activity.isInPictureInPictureMode()` for the last resumed activity, and surface them on `BridgeStatus`.

- [ ] **Step 1: Read the existing runtime to find the `status()` impl**

  ```bash
  grep -n "override suspend fun status\|FixThisBridgeRuntime\|class FixThisBridgeEnvironment" fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt | head -10
  ```
  Expected: a line near 354 like `override suspend fun status(): BridgeStatus {` inside the runtime, plus the surrounding holder for `Application` / lifecycle callbacks.

- [ ] **Step 2: Write the failing test**

  Append the following tests to `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt` (extend the file from Task 1; do not duplicate the existing test class declaration):

  ```kotlin
  // Add inside the same BridgeStatusAvailabilityTest class

  @Test
  fun `runtime status reports availability from collaborators`() = runBlocking {
      val app = mock(Application::class.java)
      val powerManager = mock(PowerManager::class.java)
      val keyguardManager = mock(KeyguardManager::class.java)
      whenever(app.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)
      whenever(app.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(keyguardManager)
      whenever(powerManager.isInteractive).thenReturn(false)
      whenever(keyguardManager.isKeyguardLocked).thenReturn(true)

      val lifecycle = FixThisActivityLifecycleCallbacks()
      val activity = mock(Activity::class.java)
      whenever(activity.isInPictureInPictureMode).thenReturn(true)
      lifecycle.onActivityResumed(activity)

      val environment = FixThisBridgeEnvironment(
          application = app,
          lifecycleCallbacks = lifecycle,
          // existing dependencies – pass mocks/no-ops as needed by ctor
      )

      val status = environment.status()
      assertEquals(false, status.screenInteractive)
      assertEquals(true, status.keyguardLocked)
      assertEquals(true, status.appForeground)
      assertEquals(true, status.pictureInPicture)
  }
  ```

  Add the imports at the top of the file:

  ```kotlin
  import android.app.Activity
  import android.app.Application
  import android.app.KeyguardManager
  import android.content.Context
  import android.os.PowerManager
  import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
  import kotlinx.coroutines.runBlocking
  import org.junit.runner.RunWith
  import org.mockito.Mockito.mock
  import org.mockito.kotlin.whenever
  import org.robolectric.RobolectricTestRunner
  ```

  Annotate the class with `@RunWith(RobolectricTestRunner::class)`.

  > Note: `FixThisBridgeEnvironment` is the public class name used by the runtime; if the existing class is named differently (e.g., `FixThisBridgeRuntimeEnvironment`), use that name verbatim. The grep in Step 1 reveals the exact name.

- [ ] **Step 3: Run test, verify it fails**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "*BridgeStatusAvailabilityTest.runtime status reports availability from collaborators*"
  ```
  Expected: failure (signals not yet wired into status payload).

- [ ] **Step 4: Implement the wiring**

  In `BridgeServer.kt`, locate the `BridgeEnvironment` interface (~line 197) and the runtime impl (~line 289 / 354). Make two changes:

  1. Inject the lifecycle callbacks into the runtime's environment constructor (or fetch them from the existing `FixThisBridgeRuntime` static holder where they were registered in `FixThis.start`).
  2. In the `status()` override, replace the existing `BridgeStatus(...)` construction so it includes the four new fields. Example pattern (adapt to the actual constructor parameters in your tree):

  ```kotlin
  override suspend fun status(): BridgeStatus {
      val powerManager = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val keyguardManager = application.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val resumedActivity = lifecycleCallbacks.lastResumedActivity()

      return BridgeStatus(
          activity = currentActivityName(),
          rootsCount = currentRootsCount(),
          sidekickVersion = SIDEKICK_VERSION,
          bridgeProtocolVersion = BRIDGE_PROTOCOL_VERSION,
          sourceIndexAvailable = sourceIndex.isAvailable(),
          capabilities = capabilities(),
          screenInteractive = powerManager?.isInteractive,
          keyguardLocked = keyguardManager?.isKeyguardLocked,
          appForeground = lifecycleCallbacks.isAppForeground(),
          pictureInPicture = resumedActivity?.isInPictureInPictureMode,
      )
  }
  ```

  In `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThis.kt`, ensure the same `FixThisActivityLifecycleCallbacks` instance is passed both to `Application.registerActivityLifecycleCallbacks(...)` and into the runtime initializer (extract a single `val callbacks = FixThisActivityLifecycleCallbacks()` into the existing `init` block and pass it into `FixThisBridgeRuntime.start(application, callbacks)`).

- [ ] **Step 5: Run all sidekick tests**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest
  ```
  Expected: all tests pass; new test from Step 2 passes.

- [ ] **Step 6: Commit**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
          fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/FixThis.kt \
          fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusAvailabilityTest.kt
  git commit -m "feat(sidekick): emit screenInteractive/keyguardLocked/appForeground/pictureInPicture in status()"
  ```

---

## Task 4 — MCP: surface availability fields on `ConsoleConnectionStatus`

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt:111-124`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

**Goal:** On the READY branch of `connectionStatus()`, call `bridge.status(packageName)` (the bridge already exposes it), parse the four boolean signals plus `rootsCount`, and add them to `ConsoleConnectionStatus.availability`. Pass-through is null-safe so older sidekicks omit the fields.

- [ ] **Step 1: Add the model**

  Append to `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt`:

  ```kotlin
  @Serializable
  data class ConsoleAvailabilitySignals(
      val screenInteractive: Boolean? = null,
      val keyguardLocked: Boolean? = null,
      val appForeground: Boolean? = null,
      val pictureInPicture: Boolean? = null,
      val rootsCount: Int? = null,
  )
  ```

  And add a nullable property `availability` to `ConsoleConnectionStatus` (insert before `details = ConsoleConnectionDetails(...)`):

  ```kotlin
  val availability: ConsoleAvailabilitySignals? = null,
  ```

- [ ] **Step 2: Write the failing test**

  Open `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`. Find an existing test that hits `/api/connection` with a fake bridge (search for `"/api/connection"`) and either extend it or add a sibling test:

  ```kotlin
  @Test
  fun `connection status surfaces availability signals from bridge status`() {
      val bridge = FakeBridge(
          devicesProvider = { listOf(AdbDevice("serial", "device", model = "Pixel")) },
          selectedSerialProvider = { "serial" },
          heartbeatProvider = { JsonObject(emptyMap()) },
          statusProvider = {
              buildJsonObject {
                  put("activity", JsonPrimitive("MainActivity"))
                  put("rootsCount", JsonPrimitive(2))
                  put("sidekickVersion", JsonPrimitive("1"))
                  put("bridgeProtocolVersion", JsonPrimitive("1"))
                  put("sourceIndexAvailable", JsonPrimitive(true))
                  put("screenInteractive", JsonPrimitive(false))
                  put("keyguardLocked", JsonPrimitive(true))
                  put("appForeground", JsonPrimitive(true))
                  put("pictureInPicture", JsonPrimitive(false))
              }
          },
      )
      // serve a session via the existing harness
      val response = server.fetchConnection() // helper used in existing tests
      assertEquals(false, response.availability?.screenInteractive)
      assertEquals(true, response.availability?.keyguardLocked)
      assertEquals(true, response.availability?.appForeground)
      assertEquals(false, response.availability?.pictureInPicture)
      assertEquals(2, response.availability?.rootsCount)
  }

  @Test
  fun `legacy bridge without availability fields produces null availability`() {
      val bridge = FakeBridge(
          devicesProvider = { listOf(AdbDevice("serial", "device")) },
          selectedSerialProvider = { "serial" },
          heartbeatProvider = { JsonObject(emptyMap()) },
          statusProvider = {
              buildJsonObject {
                  put("rootsCount", JsonPrimitive(0))
                  put("sidekickVersion", JsonPrimitive("1"))
                  put("bridgeProtocolVersion", JsonPrimitive("1"))
                  put("sourceIndexAvailable", JsonPrimitive(true))
              }
          },
      )
      val response = server.fetchConnection()
      assertNull(response.availability?.screenInteractive)
      assertNull(response.availability?.keyguardLocked)
      assertNull(response.availability?.appForeground)
      assertNull(response.availability?.pictureInPicture)
      assertEquals(0, response.availability?.rootsCount) // rootsCount always present
  }
  ```

  > If `FakeBridge` in this test file does not yet have a `statusProvider` parameter, add it: it must satisfy `suspend fun status(packageName: String): JsonObject`. Default it to `JsonObject(emptyMap())` so existing tests stay green.

- [ ] **Step 3: Run test, verify it fails**

  ```bash
  ./gradlew :fixthis-mcp:test --tests "*FeedbackConsoleServerTest.connection status surfaces availability*"
  ```
  Expected: failure (no `availability` populated).

- [ ] **Step 4: Implement in `ConsoleConnectionService`**

  Update the READY branch (around `ConsoleConnectionService.kt:111-124`):

  ```kotlin
  return try {
      bridge.heartbeat(session.packageName)
      val availability = readAvailabilitySignals(session.packageName)
      ConsoleConnectionStatus(
          state = ConsoleConnectionState.READY,
          headline = "Ready",
          message = "Your app is connected.",
          primaryAction = ConsoleConnectionAction.CAPTURE,
          selectedDevice = selectedDevice.toConnectionDevice(selectedSerial),
          devices = connectionDevices,
          packageName = session.packageName,
          canCapture = true,
          canNavigate = true,
          availability = availability,
          details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "connected"),
      )
  } catch (...
  ```

  Add the helper to the same class:

  ```kotlin
  private suspend fun readAvailabilitySignals(packageName: String): ConsoleAvailabilitySignals? =
      try {
          val payload = bridge.status(packageName)
          ConsoleAvailabilitySignals(
              screenInteractive = payload.bool("screenInteractive"),
              keyguardLocked = payload.bool("keyguardLocked"),
              appForeground = payload.bool("appForeground"),
              pictureInPicture = payload.bool("pictureInPicture"),
              rootsCount = payload.int("rootsCount"),
          )
      } catch (error: CancellationException) {
          throw error
      } catch (_: Throwable) {
          null
      }

  private fun JsonObject.bool(key: String): Boolean? =
      (this[key] as? JsonPrimitive)?.booleanOrNull

  private fun JsonObject.int(key: String): Int? =
      (this[key] as? JsonPrimitive)?.intOrNull
  ```

  Add imports:
  ```kotlin
  import io.beyondwin.fixthis.mcp.console.ConsoleAvailabilitySignals
  import kotlinx.serialization.json.JsonObject
  import kotlinx.serialization.json.JsonPrimitive
  import kotlinx.serialization.json.booleanOrNull
  import kotlinx.serialization.json.intOrNull
  ```

- [ ] **Step 5: Run all MCP tests**

  ```bash
  ./gradlew :fixthis-mcp:test
  ```
  Expected: all tests pass.

- [ ] **Step 6: Commit**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt \
          fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
  git commit -m "feat(mcp): surface bridge availability signals on console connection status"
  ```

---

## Task 5 — Console: pure `availability.js` module + Node tests

**Files:**

- Create: `fixthis-mcp/src/main/console/availability.js`
- Create: `scripts/console-availability-test.mjs`
- Modify: `package.json` (add `console:availability:test`)
- Modify: `scripts/build-console-assets.mjs` (insert `'availability.js'` in the `sources` array, immediately after `'connection.js'`)

**Goal:** Implement the priority-based reason resolver, the 300 ms debouncer, and the failure-streak unresponsive tracker as a self-contained pure module with no DOM access. Cover them with `node --test`.

- [ ] **Step 1: Write the failing tests**

  Create `scripts/console-availability-test.mjs`:

  ```js
  import { test } from 'node:test';
  import assert from 'node:assert/strict';
  import { readFileSync } from 'node:fs';
  import { dirname, resolve } from 'node:path';
  import { fileURLToPath } from 'node:url';

  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/availability.js'), 'utf8');

  // The console bundle is hoisted into a function scope. Evaluate with `new Function`
  // and return the public surface for testing.
  const factory = new Function(`${source}; return { computeBlockedReason, createBlockedReasonDebouncer, createUnresponsiveTracker };`);
  const { computeBlockedReason, createBlockedReasonDebouncer, createUnresponsiveTracker } = factory();

  test('priority order: screenOff beats everything', () => {
      const status = { screenInteractive: false, keyguardLocked: true, appForeground: false, pictureInPicture: true, unresponsive: true, rootsCount: 0 };
      assert.equal(computeBlockedReason(status, true), 'screenOff');
  });

  test('priority order: locked beats background and below', () => {
      assert.equal(
          computeBlockedReason({ screenInteractive: true, keyguardLocked: true, appForeground: false }, true),
          'locked'
      );
  });

  test('priority order: background beats pip and below', () => {
      assert.equal(
          computeBlockedReason({ screenInteractive: true, keyguardLocked: false, appForeground: false, pictureInPicture: true }, true),
          'background'
      );
  });

  test('priority order: pictureInPicture beats unresponsive', () => {
      assert.equal(
          computeBlockedReason({ screenInteractive: true, appForeground: true, pictureInPicture: true, unresponsive: true }, true),
          'pictureInPicture'
      );
  });

  test('unresponsive beats noComposeUi', () => {
      assert.equal(
          computeBlockedReason({ screenInteractive: true, appForeground: true, unresponsive: true, rootsCount: 0 }, true),
          'unresponsive'
      );
  });

  test('noComposeUi only applies in annotate mode', () => {
      const status = { screenInteractive: true, appForeground: true, rootsCount: 0 };
      assert.equal(computeBlockedReason(status, true), 'noComposeUi');
      assert.equal(computeBlockedReason(status, false), null);
  });

  test('null status returns null', () => {
      assert.equal(computeBlockedReason(null, true), null);
  });

  test('all-clear returns null', () => {
      assert.equal(
          computeBlockedReason({ screenInteractive: true, keyguardLocked: false, appForeground: true, pictureInPicture: false, rootsCount: 1 }, true),
          null
      );
  });

  test('legacy status with all nullable fields null returns null', () => {
      assert.equal(computeBlockedReason({ rootsCount: 1 }, true), null);
  });

  test('debouncer applies blocked transitions after delay', async () => {
      let now = 0;
      const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
      assert.equal(debouncer.observe('background'), null); // first observation, not yet committed
      now = 200;
      assert.equal(debouncer.observe('background'), null); // still under threshold
      now = 350;
      assert.equal(debouncer.observe('background'), 'background'); // committed
  });

  test('debouncer applies unblocked transitions immediately', () => {
      let now = 0;
      const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
      now = 350;
      debouncer.observe('background'); // commit blocked
      now = 360;
      assert.equal(debouncer.observe(null), null); // immediate clear
  });

  test('debouncer cancels pending block when observation resets to null', () => {
      let now = 0;
      const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
      debouncer.observe('background');
      now = 100;
      assert.equal(debouncer.observe(null), null);
      now = 500;
      assert.equal(debouncer.observe(null), null);
  });

  test('unresponsiveTracker flips after 3 consecutive failures', () => {
      const tracker = createUnresponsiveTracker({ threshold: 3 });
      assert.equal(tracker.observeFailure(), false);
      assert.equal(tracker.observeFailure(), false);
      assert.equal(tracker.observeFailure(), true);
  });

  test('unresponsiveTracker resets on success', () => {
      const tracker = createUnresponsiveTracker({ threshold: 3 });
      tracker.observeFailure();
      tracker.observeFailure();
      tracker.observeSuccess();
      assert.equal(tracker.observeFailure(), false); // streak restarted
  });

  test('unresponsiveTracker backoff schedule', () => {
      const tracker = createUnresponsiveTracker({ threshold: 3 });
      assert.equal(tracker.nextBackoffMs(), 1000);
      tracker.observeFailure();
      assert.equal(tracker.nextBackoffMs(), 2000);
      tracker.observeFailure();
      assert.equal(tracker.nextBackoffMs(), 5000);
      tracker.observeFailure();
      assert.equal(tracker.nextBackoffMs(), 10000);
      tracker.observeFailure();
      assert.equal(tracker.nextBackoffMs(), 30000);
      tracker.observeFailure();
      assert.equal(tracker.nextBackoffMs(), 30000); // capped
  });
  ```

- [ ] **Step 2: Run test, verify it fails**

  ```bash
  node --test scripts/console-availability-test.mjs
  ```
  Expected: failure — `availability.js` does not exist.

- [ ] **Step 3: Implement the module**

  Create `fixthis-mcp/src/main/console/availability.js`:

  ```js
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
  ```

  Update `scripts/build-console-assets.mjs` so the new module is bundled. Find the `sources` array (currently lines 8-19) and insert `'availability.js'` after `'connection.js'`:

  ```js
  const sources = [
    'state.js',
    'api.js',
    'connection.js',
    'availability.js',
    'devices.js',
    'preview.js',
    'annotations.js',
    'history.js',
    'prompt.js',
    'rendering.js',
    'shortcuts.js',
    'main.js',
  ];
  ```

  Update `package.json` scripts to add the new test entry:

  ```json
  "scripts": {
    "console:smoke": "node scripts/console-browser-smoke.mjs",
    "console:availability:test": "node --test scripts/console-availability-test.mjs"
  }
  ```

- [ ] **Step 4: Run tests, rebuild bundle, verify**

  ```bash
  node --test scripts/console-availability-test.mjs
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```
  Expected: all 14 tests pass; bundler `--check` exits 0 silently; `node --check` is silent.

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/availability.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/build-console-assets.mjs \
          scripts/console-availability-test.mjs \
          package.json
  git commit -m "feat(console): add availability module with priority resolver, debouncer, unresponsive tracker"
  ```

---

## Task 6 — Console: integrate availability into state and connection refresh

**Files:**

- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`

**Goal:** Wire `state.connection.interactionBlockedReason`, `state.connection.availability`, the unresponsive tracker, and the debouncer into the connection refresh loop. Adjust polling interval based on the unresponsive backoff.

- [ ] **Step 1: Extend state shape**

  Edit `fixthis-mcp/src/main/console/state.js` and find the `state.connection = { ... }` initializer (around line 13). Add fields:

  ```js
  state.connection = {
    current: null,
    hasEverConnected: false,
    lastReadyAt: null,
    launchInFlight: false,
    availability: null,                  // raw signals from server
    interactionBlockedReason: null,      // 'screenOff' | 'locked' | 'background' | 'pictureInPicture' | 'unresponsive' | 'noComposeUi' | null
  };
  ```

  Below the state declaration (still within the IIFE), instantiate the trackers exactly once:

  ```js
  const blockedReasonDebouncer = createBlockedReasonDebouncer({ delayMs: 300 });
  const unresponsiveTracker = createUnresponsiveTracker({ threshold: 3 });
  ```

- [ ] **Step 2: Update connection refresh**

  Edit `fixthis-mcp/src/main/console/connection.js`. In `refreshConnection` (the function that calls `apiGetConnection()` and assigns `state.connection.current = status`), add availability handling immediately after the `state.connection.current = status;` line:

  ```js
  state.connection.availability = status?.availability ?? null;

  // Combine availability with the unresponsive tracker for the resolver input.
  const annotate = toolMode === 'annotate';
  const resolverInput = state.connection.availability
    ? { ...state.connection.availability, unresponsive: unresponsiveTracker.isUnresponsive() }
    : { unresponsive: unresponsiveTracker.isUnresponsive() };
  const rawReason = computeBlockedReason(resolverInput, annotate);
  state.connection.interactionBlockedReason = blockedReasonDebouncer.observe(rawReason);

  // success → clear failure streak
  unresponsiveTracker.observeSuccess();
  ```

  In the `catch` branch of `refreshConnection`, where the network request fails, register a failure and force the resolver to re-evaluate so the overlay can show `unresponsive` once the streak crosses the threshold:

  ```js
  } catch (error) {
    unresponsiveTracker.observeFailure();
    const annotate = toolMode === 'annotate';
    const resolverInput = state.connection.availability
      ? { ...state.connection.availability, unresponsive: unresponsiveTracker.isUnresponsive() }
      : { unresponsive: unresponsiveTracker.isUnresponsive() };
    const rawReason = computeBlockedReason(resolverInput, annotate);
    state.connection.interactionBlockedReason = blockedReasonDebouncer.observe(rawReason);
    showError(error);
  }
  ```

  In the polling loop (look for the `setTimeout(refreshConnection, ...)` or equivalent — around line 128), pull the next interval from the tracker:

  ```js
  const nextDelayMs = unresponsiveTracker.nextBackoffMs();
  setTimeout(() => refreshConnection().catch(showError), nextDelayMs);
  ```

  > If the existing polling cadence is fixed at 800–1000 ms via a single literal, replace that literal with `nextDelayMs`. If polling is event-driven (no setTimeout), skip this sub-step and document at the bottom of `refreshConnection` that backoff is not applicable; the tracker will still gate `interactionBlockedReason`.

- [ ] **Step 3: Rebuild and syntax-check**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```
  Expected: all silent, exit 0.

- [ ] **Step 4: Manual smoke (browser DevTools)**

  Start the console (see CLAUDE.md "Console UI Development"), open DevTools, and inject a mock blocked status to confirm wiring before committing:

  ```js
  state.connection.availability = { screenInteractive: false, appForeground: true };
  // trigger a refresh tick or call computeBlockedReason directly
  computeBlockedReason({ ...state.connection.availability, unresponsive: false }, true);
  // → 'screenOff'
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/state.js \
          fixthis-mcp/src/main/console/connection.js \
          fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): integrate availability tracker and interactionBlockedReason into connection refresh"
  ```

---

## Task 7 — Console: canvas overlay (HTML, CSS, render hook)

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js` (call site)

**Goal:** Render a semi-transparent overlay above the canvas reflecting `state.connection.interactionBlockedReason`. The frozen frame stays visible underneath. The overlay disappears the moment `interactionBlockedReason` becomes null.

- [ ] **Step 1: Add overlay container**

  In `fixthis-mcp/src/main/resources/console/index.html`, locate the canvas wrapper element (search for `id="canvas"` or the parent `<div>` of `<img id="canvasImage">`). Append a sibling overlay node *inside* the wrapper so it stacks above the image:

  ```html
  <div id="canvasBlockedOverlay" class="canvas-blocked" hidden>
    <div class="canvas-blocked__icon" aria-hidden="true">🔒</div>
    <div class="canvas-blocked__headline" data-headline></div>
    <div class="canvas-blocked__detail" data-detail></div>
    <button type="button" class="canvas-blocked__retry" data-retry hidden>Retry now</button>
  </div>
  ```

- [ ] **Step 2: Add styles**

  Append to `fixthis-mcp/src/main/resources/console/styles.css`:

  ```css
  .canvas-blocked {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    background: rgba(8, 12, 20, 0.55);
    color: #e5e7eb;
    font-size: 14px;
    text-align: center;
    padding: 24px;
    pointer-events: auto;
    backdrop-filter: blur(2px);
  }
  .canvas-blocked[hidden] { display: none; }
  .canvas-blocked__icon { font-size: 36px; line-height: 1; }
  .canvas-blocked__headline { font-weight: 600; font-size: 16px; }
  .canvas-blocked__detail { opacity: 0.85; max-width: 360px; }
  .canvas-blocked__retry {
    margin-top: 8px;
    padding: 6px 14px;
    border: 1px solid #4b5563;
    border-radius: 6px;
    background: #1f2937;
    color: inherit;
    cursor: pointer;
  }
  .canvas-blocked__retry[hidden] { display: none; }
  ```

  Ensure the canvas wrapper already has `position: relative;`. If not, add it to the wrapper rule (search the existing selector that styles the wrapper).

- [ ] **Step 3: Implement render hook**

  In `fixthis-mcp/src/main/console/preview.js`, add a function that renders/clears the overlay based on the current state. Place it near the existing preview rendering helpers:

  ```js
  function renderCanvasBlockedOverlay() {
    const overlay = document.getElementById('canvasBlockedOverlay');
    if (!overlay) return;
    const reason = state.connection?.interactionBlockedReason ?? null;
    if (!reason) {
      overlay.hidden = true;
      return;
    }
    overlay.hidden = false;
    const headlines = {
      screenOff: 'Device screen is off',
      locked: 'Device is locked',
      background: 'Sample app is in the background',
      pictureInPicture: 'Sample app is in Picture-in-Picture',
      unresponsive: 'Sample app is unresponsive',
      noComposeUi: 'No Compose UI on this screen',
    };
    const details = {
      screenOff: 'Wake the device to continue.',
      locked: 'Unlock the device to continue.',
      background: 'Bring the sample app to the foreground.',
      pictureInPicture: 'Exit Picture-in-Picture to continue.',
      unresponsive: 'Retrying…',
      noComposeUi: 'Switch to a screen with Compose content to annotate.',
    };
    overlay.querySelector('[data-headline]').textContent = headlines[reason] ?? '';
    overlay.querySelector('[data-detail]').textContent = details[reason] ?? '';
    const retry = overlay.querySelector('[data-retry]');
    retry.hidden = reason !== 'unresponsive';
  }
  ```

  Wire the retry button once at module init (just below the function — it's idempotent because it queries by id):

  ```js
  document.getElementById('canvasBlockedOverlay')?.querySelector('[data-retry]')?.addEventListener('click', () => {
    refreshConnection().catch(showError);
  });
  ```

- [ ] **Step 4: Call the hook from the render loop**

  In `fixthis-mcp/src/main/console/rendering.js`, find the main `render()` function (the one called after state mutations). Add a call to `renderCanvasBlockedOverlay()` near the existing canvas-related render calls. Example placement — directly after `renderSelectionOverlay()`:

  ```js
  renderSelectionOverlay();
  renderCanvasBlockedOverlay();
  ```

- [ ] **Step 5: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```
  Expected: silent, exit 0.

- [ ] **Step 6: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html \
          fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/preview.js \
          fixthis-mcp/src/main/console/rendering.js \
          fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): render canvas blocked-reason overlay with per-cause messaging"
  ```

---

## Task 8 — Console: gate click/drag and remove misleading errors

**Files:**

- Modify: `fixthis-mcp/src/main/console/annotations.js`

**Goal:** When `state.connection.interactionBlockedReason !== null`, all canvas pointer handlers must early-return without creating selections or surfacing the "No component found" error.

- [ ] **Step 1: Add a gate helper at the top of `annotations.js`**

  Insert near the existing module-level helpers:

  ```js
  function isInteractionBlocked() {
    return Boolean(state.connection?.interactionBlockedReason);
  }
  ```

- [ ] **Step 2: Gate the entry points**

  Add the early-return to each canvas pointer handler. The locations are:

  - `selectNodeAtPoint` (annotations.js around line 922 in the bundled file; in source the function lives in `annotations.js`). Insert at the top of the function body:

    ```js
    if (isInteractionBlocked()) return;
    ```

  - `previewNodeAtPoint` — same first-line gate:

    ```js
    if (isInteractionBlocked()) return;
    ```

  - `confirmHoveredAnnotationTarget` — same first-line gate.

  - `finishAreaSelection` — same first-line gate.

  - The existing call:

    ```js
    showError(new Error('No component found at that point. Drag to select a custom area.'));
    ```

    Replace it with:

    ```js
    if (!isInteractionBlocked()) {
      showError(new Error('No component found at that point. Drag to select a custom area.'));
    }
    ```

    The gate above already prevents reaching this line under blocked state, but the defensive check guarantees the message can never re-appear if a future caller bypasses the entry-point gate.

- [ ] **Step 3: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```
  Expected: silent, exit 0.

- [ ] **Step 4: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/annotations.js \
          fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): suppress canvas selection and misleading error while interaction is blocked"
  ```

---

## Task 9 — Console: top bar device chip suffix

**Files:**

- Modify: `fixthis-mcp/src/main/console/devices.js`
- Modify: `fixthis-mcp/src/main/console/connection.js` (chip render call site, if it lives there instead)

**Goal:** Append a compact suffix to the active device chip whenever `interactionBlockedReason` is non-null, e.g. `Connected · Screen off`. Inactive devices keep their existing label.

- [ ] **Step 1: Identify the chip render path**

  ```bash
  grep -n "Connected\|Connecting\|Unavailable\|deviceControl\|connectionCard" fixthis-mcp/src/main/console/devices.js fixthis-mcp/src/main/console/connection.js | head -30
  ```
  Expected: a render function in one of the files that maps `DeviceUiState` to the chip label.

- [ ] **Step 2: Add suffix mapping**

  In whichever file owns the render, add:

  ```js
  const BLOCKED_SUFFIX = {
    screenOff: 'Screen off',
    locked: 'Locked',
    background: 'In background',
    pictureInPicture: 'PiP',
    unresponsive: 'Unresponsive',
    noComposeUi: 'No Compose UI',
  };

  function decorateConnectionLabel(baseLabel, reason) {
    if (!reason) return baseLabel;
    const suffix = BLOCKED_SUFFIX[reason];
    return suffix ? `${baseLabel} · ${suffix}` : baseLabel;
  }
  ```

  Use `decorateConnectionLabel` wherever the chip's text content is set. For the active device's `Connected` label, pass `state.connection.interactionBlockedReason`. For inactive chips, pass `null` so the label is unchanged.

- [ ] **Step 3: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/devices.js \
          fixthis-mcp/src/main/console/connection.js \
          fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): annotate device chip label with blocked-reason suffix"
  ```

---

## Task 10 — Console: state preservation + auto-resume

**Files:**

- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`

**Goal:** Preserve `toolMode`, `state.preview`, and `pendingFeedbackItems` across blocked → unblocked and Unavailable → Connected transitions. Restore silently if the new activity matches the frozen one; otherwise mark the frame stale and surface a `Use latest frame` button.

- [ ] **Step 1: Stop nullifying preserved state on Unavailable**

  In `connection.js`, find any place that sets `state.preview = null;` or `pendingFeedbackItems.length = 0;` in response to an Unavailable transition and remove or guard those lines. Keep `state.preview = null` calls that fire on explicit user actions (Clear selection, Exit Annotate). Add a comment immediately above the change pointing to this task:

  ```js
  // Preserve frozen preview and pending pins across Unavailable so reconnect can auto-resume.
  ```

- [ ] **Step 2: Add `activity` to `ConsoleAvailabilitySignals` and to the frozen preview**

  Extend the model created in Task 4. In `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt` add the field to `ConsoleAvailabilitySignals`:

  ```kotlin
  val activity: String? = null,
  ```

  In `ConsoleConnectionService.readAvailabilitySignals`, populate it from the same `bridge.status` payload:

  ```kotlin
  activity = (payload["activity"] as? JsonPrimitive)?.contentOrNull,
  ```

  In `preview.js` (and any other writer of `state.preview`), include the activity name when freezing the preview:

  ```js
  state.preview = {
    previewId: ...,
    screen: ...,
    screenshotUrl: ...,
    activity: state.connection?.availability?.activity ?? null,
  };
  ```

- [ ] **Step 3: Detect activity mismatch on reconnect**

  In `connection.js`, inside the success branch of `refreshConnection` (after `state.connection.hasEverConnected = true;`), add:

  ```js
  const restoredActivity = state.preview?.activity ?? null;
  const currentActivity = status?.availability?.activity ?? null;
  if (state.preview && restoredActivity && currentActivity) {
    state.preview.stale = restoredActivity !== currentActivity;
  } else if (state.preview) {
    state.preview.stale = false;
  }
  ```

- [ ] **Step 4: Render the "Use latest frame" affordance**

  In `preview.js` add a small renderer:

  ```js
  function renderStaleFrameNotice() {
    const root = document.getElementById('canvasStaleNotice');
    if (!root) return;
    if (state.preview?.stale) {
      root.hidden = false;
    } else {
      root.hidden = true;
    }
  }
  ```

  Add the DOM node to `index.html` next to the canvas overlay container:

  ```html
  <div id="canvasStaleNotice" class="canvas-stale" hidden>
    <span>Frozen frame may be out of date.</span>
    <button type="button" data-use-latest>Use latest frame</button>
  </div>
  ```

  Add a minimal style block to `styles.css`:

  ```css
  .canvas-stale {
    position: absolute;
    top: 12px;
    left: 12px;
    right: 12px;
    background: rgba(120, 53, 15, 0.85);
    color: #fff7ed;
    padding: 8px 12px;
    border-radius: 6px;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .canvas-stale[hidden] { display: none; }
  ```

  Wire the button in `preview.js` once:

  ```js
  document.getElementById('canvasStaleNotice')?.querySelector('[data-use-latest]')?.addEventListener('click', () => {
    state.preview = null;
    pendingFeedbackItems.length = 0; // drop pins anchored to the stale frame
    refreshLatestPreview().catch(showError); // existing helper that re-freezes the latest frame
    render();
  });
  ```

  Call `renderStaleFrameNotice()` from the same place as `renderCanvasBlockedOverlay()` in `rendering.js`.

- [ ] **Step 5: Auto-resume polling on unblock**

  In `connection.js`, when `interactionBlockedReason` transitions from non-null to null and `toolMode === 'select'`, call the existing live-preview polling primer (search for `schedulePreviewPolling` or whichever helper Select mode uses; there is one because the existing UI says "Preview polling pauses while the browser tab is hidden"). If the project's polling is event-driven without an explicit primer, invoking `refreshLatestPreview()` once is sufficient. Concretely, after the line:

  ```js
  state.connection.interactionBlockedReason = blockedReasonDebouncer.observe(rawReason);
  ```

  add:

  ```js
  if (
    state.connection.previousBlockedReason !== null &&
    state.connection.interactionBlockedReason === null
  ) {
    if (toolMode === 'select') {
      refreshLatestPreview().catch(showError);
    }
  }
  state.connection.previousBlockedReason = state.connection.interactionBlockedReason;
  ```

  Initialize `state.connection.previousBlockedReason = null` in `state.js`.

- [ ] **Step 6: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  node --check fixthis-mcp/src/main/resources/console/app.js
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/state.js \
          fixthis-mcp/src/main/console/connection.js \
          fixthis-mcp/src/main/console/preview.js \
          fixthis-mcp/src/main/console/annotations.js \
          fixthis-mcp/src/main/resources/console/index.html \
          fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): preserve frozen preview and pins across blocked/Unavailable, auto-resume on unblock"
  ```

---

## Task 11 — Browser smoke test extension

**Files:**

- Modify: `scripts/console-browser-smoke.mjs`

**Goal:** Extend the existing Playwright smoke harness with a fixture that toggles `availability.screenInteractive` between `false` and `true` while in Annotate mode and asserts: overlay visible during off, overlay gone during on, no spurious `error.textContent`, and `state.preview` plus pending pins survive the round trip.

- [ ] **Step 1: Add the fixture toggle**

  Find the section of the smoke script that defines `fake.handlers` (or the equivalent intercept layer; search for `/api/connection`). Add an `availability` field to the fake connection payload that the test can mutate:

  ```js
  fake.availability = { screenInteractive: true, keyguardLocked: false, appForeground: true, pictureInPicture: false, rootsCount: 1 };

  // Inside the /api/connection handler payload:
  payload.availability = { ...fake.availability };
  ```

- [ ] **Step 2: Add the new test scenario**

  After the existing scenarios, add:

  ```js
  // Scenario: screen-off blocks annotation and auto-resumes.
  await page.evaluate(() => window.toolMode = 'annotate');
  await page.evaluate(() => window.fake.availability.screenInteractive = false);
  await page.waitForFunction(() => document.getElementById('canvasBlockedOverlay')?.hidden === false, { timeout: 5000 });
  // Click the canvas while blocked – should not produce a selection or error.
  await page.click('#canvasImage');
  const errAfterClick = await page.evaluate(() => document.getElementById('error')?.textContent ?? '');
  assert.equal(errAfterClick, '');
  await page.evaluate(() => window.fake.availability.screenInteractive = true);
  await page.waitForFunction(() => document.getElementById('canvasBlockedOverlay')?.hidden === true, { timeout: 5000 });
  // Frozen preview and pins should still be present.
  const preserved = await page.evaluate(() => ({
    hasPreview: Boolean(window.state?.preview),
    pendingCount: window.pendingFeedbackItems?.length ?? 0,
  }));
  assert.equal(preserved.hasPreview, true);
  ```

  > The exact id `error` and globals (`window.state`, `window.toolMode`, `window.pendingFeedbackItems`, `window.fake`) follow the patterns already used elsewhere in the script. If a global is module-scoped (not on `window`), expose it temporarily via `window.<name> = <name>;` in the existing test bootstrap.

- [ ] **Step 3: Run the smoke**

  ```bash
  npm run console:smoke
  ```
  Expected: the new scenario passes alongside existing ones.

- [ ] **Step 4: Commit**

  ```bash
  git add scripts/console-browser-smoke.mjs
  git commit -m "test(console): smoke-test screen-off overlay, gating, and auto-resume"
  ```

---

## Task 12 — Manual verification + CHANGELOG

**Files:**

- Modify: `CHANGELOG.md`

**Goal:** Run the eight manual scenarios from the spec on a real Android device and document the change.

- [ ] **Step 1: Build the CLI + sample app + sidekick**

  ```bash
  ./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist :app:assembleDebug
  ```

- [ ] **Step 2: Launch the console**

  ```bash
  fixthis-cli/build/install/fixthis/bin/fixthis console \
    --package io.beyondwin.fixthis.sample \
    --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
  ```

  Open the printed URL in a browser, click Start, enter Annotate mode, and place two pins on a frozen frame.

- [ ] **Step 3: Walk the eight scenarios from the spec (§9.4)**

  For each scenario, confirm:
  - Correct headline / detail text appears on the overlay.
  - Canvas clicks during blocked do not create selections and `error` element stays empty.
  - On resolution, `toolMode`, `state.preview`, and `pendingFeedbackItems` survive.
  - Top bar chip shows the correct suffix.

  Scenarios:
  1. Power button (screen off → on)
  2. Home button (background → return)
  3. Lock device (locked → unlock)
  4. Picture-in-Picture (enter → exit)
  5. ANR (`adb shell kill -STOP $(adb shell pidof io.beyondwin.fixthis.sample)` → `kill -CONT`)
  6. Switch to a non-Compose activity (if the sample includes one; otherwise document as N/A)
  7. Pull USB → reconnect with same activity → reconnect with different activity
  8. Rapid rotation × 2 (no overlay flicker)

- [ ] **Step 4: Add the CHANGELOG entry**

  Open `CHANGELOG.md` and add a top-of-Unreleased entry like:

  ```markdown
  ### Added
  - Console now models a `Connected` sub-state for screen-off, app-backgrounded, lock screen, Picture-in-Picture, unresponsive sample app, and "no Compose UI on this screen", with a canvas overlay, canvas-input gating, top-bar chip suffix, and automatic resume of the prior tool mode, frozen preview, and pending pins when the cause clears. Sidekick exposes `screenInteractive`, `keyguardLocked`, `appForeground`, and `pictureInPicture` on `BridgeStatus`.
  ```

- [ ] **Step 5: Run all unit suites**

  ```bash
  ./gradlew :fixthis-compose-core:test \
            :fixthis-cli:test \
            :fixthis-mcp:test \
            :fixthis-compose-sidekick:testDebugUnitTest \
            :fixthis-gradle-plugin:test
  node --test scripts/console-availability-test.mjs
  npm run console:smoke
  ```
  Expected: every suite green.

- [ ] **Step 6: Final commit**

  ```bash
  git add CHANGELOG.md
  git commit -m "docs: changelog entry for device interaction availability UX"
  ```
