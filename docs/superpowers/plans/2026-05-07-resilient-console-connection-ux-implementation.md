# Resilient Console Connection UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FixThis browser console easy to start and recover for non-developer users by showing a simple connection card, launching the app from the console, preserving in-progress work during disconnects, and hiding technical causes behind Details.

**Architecture:** Keep the Android app UI minimal: the app-side pill still only shows `MCP waiting` or `MCP connected` from authenticated browser heartbeat. Move all diagnosis and control into `fixthis-mcp`: add console connection DTOs, app launch bridge support, `/api/connection`, `/api/app/launch`, and a browser-side recovery state machine. The existing compact device selector remains available, but the main user-facing flow becomes one status plus one primary action.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, `com.sun.net.httpserver.HttpServer`, Android ADB via `fixthis-cli`, vanilla HTML/CSS/JS console assets, Gradle/JUnit tests.

---

## Current Project Baseline

- Mainline already contains `9c0a1ff Use heartbeat for MCP connected status` and `a22019d Move MCP status pill to app panel window`.
- App-side sidekick status is intentionally simple and heartbeat-driven:
  - `MCP waiting`
  - `MCP connected`
- Console already has:
  - `/api/devices`
  - `/api/device/select`
  - `/api/device/disconnect`
  - `/api/heartbeat`
  - live preview polling
  - auto-select when exactly one ready device exists
- Console currently does not have:
  - an app launch API
  - a single connection diagnosis API
  - user-facing recovery states such as `Open the app` or `Reconnect`
  - a saved-work disconnect banner
  - explicit stale/failed bridge classification
- Current working tree contains an unrelated modified test file:
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
  - The diff adds assertions for prompt target evidence helpers. Do not revert it. If this plan touches the same test file, preserve those assertions.

## UX Contract

### Basic User-Facing States

The browser console should show only one of these primary states in normal UI:

| State | Headline | Message | Primary action |
| --- | --- | --- | --- |
| `welcome` | `Connect to your app` | `We'll find your phone and open the app for you.` | `Start` |
| `ready` | `Ready` | `Your app is connected.` | `Capture screen` |
| `open_app` | `Open the app` | `Your phone is connected, but the app is not open.` | `Open app` |
| `starting` | `Opening app` | `We're opening the app and connecting.` | disabled spinner |
| `reconnect` | `Reconnect` | `The connection was interrupted. Your work is saved.` | `Reconnect` |
| `choose_device` | `Choose a device` | `More than one device is connected.` | device picker focus |
| `check_phone` | `Check your phone` | `Unlock your phone or allow debugging, then try again.` | `Try again` |
| `unsupported_build` | `This build cannot connect` | `Use a debuggable build with FixThis sidekick enabled.` | `Try again` |

### Work Continuity Rules

- Never clear `pendingFeedbackItems` because a heartbeat, preview, or navigation request fails.
- Keep the last preview visible during disconnects.
- Mark stale previews visually instead of blanking the canvas.
- Disable only actions that require live bridge access:
  - preview refresh
  - navigation
  - new capture
  - starting a new Add flow when no frozen preview exists
- Keep actions that use already captured browser/server state:
  - pending item focus/delete
  - comments
  - Save for an existing frozen preview
  - Copy/Send existing saved feedback
- On reconnect success, refresh preview once and resume live polling.

### Details Contract

Normal users should not see `ADB`, `LocalSocket`, `run-as`, `Bridge closed before sending a response`, package names, or raw serials unless they open `Details`.

Details should include:

```text
Device: SM_G986N · device
Package: io.beyondwin.fixthis.sample
Bridge: no response
Last connected: 12s ago
Raw error: Bridge closed before sending a response
```

## File Structure

- Modify `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Adb.kt`
  - Add `launchApp(packageName)` to `AdbFacade` and implement it with existing `monkey`.
- Modify `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
  - Add selected-device-scoped `launchApp(packageName)`.
- Modify `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/BridgeClientTest.kt`
  - Test app launch scopes to selected device and rejects unavailable devices.
- Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt`
  - Serializable DTOs for `/api/connection` and `/api/app/launch`.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
  - Expose `launchApp(packageName)` through `FixThisBridge` and `CliFixThisBridge`.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
  - Add `connectionStatus()` and `launchAppForCurrentSession()`.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
  - Add `GET /api/connection` and `POST /api/app/launch`.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt`
  - Add controllable launch and heartbeat failure behavior for tests.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt`
  - Test connection status mapping and launch flow.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
  - Test new APIs and browser asset behavior. Preserve existing uncommitted prompt evidence assertions if present.
- Modify `fixthis-mcp/src/main/resources/console/index.html`
  - Add simple recovery card and stale preview indicator anchors.
- Modify `fixthis-mcp/src/main/resources/console/styles.css`
  - Style recovery card, primary action, details disclosure, stale preview badge, and disabled live controls.
- Modify `fixthis-mcp/src/main/resources/console/app.js`
  - Replace raw heartbeat-only UI with connection polling and recovery state machine.
- Modify docs after implementation:
  - `README.md`
  - `docs/design-feedback-console-ux.md`
  - `docs/fixthis_prd.md`
  - `docs/troubleshooting.md`

---

## Task 1: Add ADB App Launch To The Bridge Layer

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Adb.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/BridgeClientTest.kt`

- [ ] **Step 1: Write failing BridgeClient launch tests**

Add tests near existing selected-device tests in `BridgeClientTest.kt`:

```kotlin
@Test
fun launchAppScopesAdbCommandToSelectedDevice() {
    val adb = FakeAdbFacade(
        sessionJson = sessionJson(protocol = "1.0"),
        devices = listOf(AdbDevice("device-1", "device")),
    )
    val client = BridgeClient(adb = adb, projectRoot = temporaryFolder.newFolder())

    client.selectDevice("device-1")
    client.launchApp("io.beyondwin.fixthis.sample")

    assertEquals(listOf("device-1" to "io.beyondwin.fixthis.sample"), adb.launchedApps)
}

@Test
fun launchAppRejectsUnavailableSelectedDevice() {
    val adb = FakeAdbFacade(
        sessionJson = sessionJson(protocol = "1.0"),
        devices = listOf(AdbDevice("device-1", "offline")),
    )
    val client = BridgeClient(adb = adb, projectRoot = temporaryFolder.newFolder())

    client.selectDevice("device-1")
    val error = kotlin.runCatching {
        client.launchApp("io.beyondwin.fixthis.sample")
    }.exceptionOrNull()

    assertTrue(error is NoDeviceException)
    assertEquals(emptyList<Pair<String?, String>>(), adb.launchedApps)
}
```

Extend the test `FakeAdbFacade` with:

```kotlin
val launchedApps: MutableList<Pair<String?, String>> = mutableListOf()

override fun launchApp(packageName: String) {
    launchedApps += selectedSerial to packageName
}
```

Make sure `forDevice(serial)` passes the shared `launchedApps` list to child fakes.

- [ ] **Step 2: Run the targeted CLI test and verify RED**

Run:

```bash
./gradlew :fixthis-cli:test --tests io.beyondwin.fixthis.cli.BridgeClientTest
```

Expected: fails because `AdbFacade.launchApp` and `BridgeClient.launchApp` do not exist.

- [ ] **Step 3: Implement ADB launch surface**

In `Adb.kt`, change `AdbFacade`:

```kotlin
interface AdbFacade {
    fun devices(): List<AdbDevice>
    fun forDevice(serial: String?): AdbFacade = this
    fun runAsCat(packageName: String, path: String): String
    fun forward(localPort: Int, socketAddress: String)
    fun removeForward(localPort: Int)
    fun pull(androidPath: String, destination: File)
    fun launchApp(packageName: String)
}
```

In `Adb`, implement:

```kotlin
override fun launchApp(packageName: String) {
    monkey(packageName)
}
```

In `BridgeClient.kt`, add:

```kotlin
fun launchApp(packageName: String) {
    val scope = requestScope()
    ensureDeviceConnected(scope.adb, scope.selectedDeviceSerial)
    scope.adb.launchApp(packageName)
}
```

- [ ] **Step 4: Run CLI tests and verify GREEN**

Run:

```bash
./gradlew :fixthis-cli:test --tests io.beyondwin.fixthis.cli.BridgeClientTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Adb.kt fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/BridgeClientTest.kt
git commit -m "feat: let bridge client launch selected app"
```

---

## Task 2: Add Console Connection DTOs And Service Diagnosis

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt`

- [ ] **Step 1: Add failing service tests**

Add these tests to `FeedbackSessionServiceTest.kt`:

```kotlin
@Test
fun connectionStatusIsReadyWhenDeviceAndHeartbeatSucceed() = runBlocking {
    val bridge = FakeFixThisBridge()
    bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    val service = serviceWithBridge(bridge)
    service.currentSession()

    val status = service.connectionStatus()

    assertEquals(ConsoleConnectionState.READY, status.state)
    assertEquals("Ready", status.headline)
    assertEquals(true, status.canCapture)
    assertEquals(true, status.canNavigate)
}

@Test
fun connectionStatusAsksToChooseDeviceWhenMultipleReadyDevicesExist() = runBlocking {
    val bridge = FakeFixThisBridge(
        devicesOverride = listOf(
            AdbDevice("device-1", "device", model = "Pixel_8"),
            AdbDevice("device-2", "device", model = "SM_G986N"),
        ),
    )
    val service = serviceWithBridge(bridge)
    service.currentSession()

    val status = service.connectionStatus()

    assertEquals(ConsoleConnectionState.CHOOSE_DEVICE, status.state)
    assertEquals("Choose a device", status.headline)
    assertEquals(false, status.canCapture)
}

@Test
fun connectionStatusAsksToOpenAppWhenDeviceIsReadyButHeartbeatFails() = runBlocking {
    val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("Bridge closed before sending a response"))
    bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    val service = serviceWithBridge(bridge)
    service.currentSession()

    val status = service.connectionStatus()

    assertEquals(ConsoleConnectionState.OPEN_APP, status.state)
    assertEquals("Open the app", status.headline)
    assertTrue(status.details.rawError.orEmpty().contains("Bridge closed before sending a response"))
}

@Test
fun launchAppForCurrentSessionDelegatesToBridgeAndReturnsStartingState() = runBlocking {
    val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
    bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    val service = serviceWithBridge(bridge)
    service.currentSession()

    val status = service.launchAppForCurrentSession()

    assertEquals(listOf("io.beyondwin.fixthis.sample"), bridge.launchedPackages)
    assertEquals(ConsoleConnectionState.STARTING, status.state)
}
```

Add imports:

```kotlin
import io.beyondwin.fixthis.cli.AdbDevice
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionState
```

If `serviceWithBridge` does not already exist, add this helper in the test class:

```kotlin
private fun serviceWithBridge(bridge: FakeFixThisBridge): FeedbackSessionService =
    FeedbackSessionService(
        bridge = bridge,
        store = FeedbackSessionStore(),
        projectRoot = temporaryFolder.newFolder().absolutePath,
        defaultPackageName = "io.beyondwin.fixthis.sample",
    )
```

- [ ] **Step 2: Run service tests and verify RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.FeedbackSessionServiceTest
```

Expected: fails because the connection DTOs and service methods do not exist.

- [ ] **Step 3: Create connection DTOs**

Create `ConsoleConnectionModels.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.AdbDevice
import kotlinx.serialization.Serializable

@Serializable
enum class ConsoleConnectionState {
    WELCOME,
    READY,
    OPEN_APP,
    STARTING,
    RECONNECT,
    CHOOSE_DEVICE,
    CHECK_PHONE,
    UNSUPPORTED_BUILD,
}

@Serializable
data class ConsoleConnectionStatus(
    val state: ConsoleConnectionState,
    val headline: String,
    val message: String,
    val primaryAction: ConsoleConnectionAction? = null,
    val selectedDevice: ConsoleConnectionDevice? = null,
    val devices: List<ConsoleConnectionDevice> = emptyList(),
    val packageName: String,
    val canCapture: Boolean = false,
    val canNavigate: Boolean = false,
    val canUseCachedWork: Boolean = true,
    val details: ConsoleConnectionDetails = ConsoleConnectionDetails(),
)

@Serializable
enum class ConsoleConnectionAction {
    START,
    OPEN_APP,
    RECONNECT,
    TRY_AGAIN,
    CHOOSE_DEVICE,
    CAPTURE,
}

@Serializable
data class ConsoleConnectionDevice(
    val serial: String,
    val state: String,
    val label: String,
    val selected: Boolean = false,
)

@Serializable
data class ConsoleConnectionDetails(
    val deviceState: String? = null,
    val bridgeState: String? = null,
    val rawError: String? = null,
)

fun AdbDevice.toConnectionDevice(selectedSerial: String?): ConsoleConnectionDevice =
    ConsoleConnectionDevice(
        serial = serial,
        state = state,
        label = model ?: deviceName ?: product ?: serial,
        selected = serial == selectedSerial,
    )
```

- [ ] **Step 4: Extend bridge interface and fake bridge**

In `FixThisTools.kt`, extend `FixThisBridge`:

```kotlin
fun launchApp(packageName: String) = Unit
```

In `CliFixThisBridge`, implement:

```kotlin
override fun launchApp(packageName: String) =
    client.launchApp(packageName)
```

In `FakeFixThisBridge.kt`, add constructor parameters and state:

```kotlin
private val devicesOverride: List<AdbDevice>? = null,
private val heartbeatError: Throwable? = null,
```

Add:

```kotlin
val launchedPackages = mutableListOf<String>()

override fun devices(): List<AdbDevice> =
    devicesOverride ?: listOf(
        AdbDevice(
            serial = "adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp",
            state = "device",
            model = "SM_G986N",
            product = "y2qksx",
            deviceName = "y2q",
        ),
    )

override suspend fun heartbeat(packageName: String): JsonObject {
    heartbeatError?.let { throw it }
    return status(packageName)
}

override fun launchApp(packageName: String) {
    launchedPackages += packageName
}
```

- [ ] **Step 5: Implement service diagnosis**

In `FeedbackSessionService.kt`, add imports:

```kotlin
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionAction
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionDetails
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionState
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
import io.beyondwin.fixthis.mcp.console.toConnectionDevice
```

Add:

```kotlin
suspend fun connectionStatus(): ConsoleConnectionStatus {
    val session = currentSession()
    val selectedSerial = bridge.selectedDeviceSerial()
    val devices = devices()
    val readyDevices = devices.filter { it.state == "device" }
    val selectedDevice = devices.firstOrNull { it.serial == selectedSerial }
    val connectionDevices = devices.map { it.toConnectionDevice(selectedSerial) }

    if (readyDevices.isEmpty()) {
        val unavailable = selectedDevice ?: devices.firstOrNull()
        return ConsoleConnectionStatus(
            state = ConsoleConnectionState.CHECK_PHONE,
            headline = "Check your phone",
            message = "Unlock your phone or allow debugging, then try again.",
            primaryAction = ConsoleConnectionAction.TRY_AGAIN,
            selectedDevice = unavailable?.toConnectionDevice(selectedSerial),
            devices = connectionDevices,
            packageName = session.packageName,
            details = ConsoleConnectionDetails(
                deviceState = unavailable?.state ?: "none",
                bridgeState = "not checked",
            ),
        )
    }

    if (selectedSerial == null) {
        return if (readyDevices.size == 1) {
            ConsoleConnectionStatus(
                state = ConsoleConnectionState.WELCOME,
                headline = "Connect to your app",
                message = "We'll find your phone and open the app for you.",
                primaryAction = ConsoleConnectionAction.START,
                devices = connectionDevices,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "not checked"),
            )
        } else {
            ConsoleConnectionStatus(
                state = ConsoleConnectionState.CHOOSE_DEVICE,
                headline = "Choose a device",
                message = "More than one device is connected.",
                primaryAction = ConsoleConnectionAction.CHOOSE_DEVICE,
                devices = connectionDevices,
                packageName = session.packageName,
                details = ConsoleConnectionDetails(deviceState = "multiple", bridgeState = "not checked"),
            )
        }
    }

    if (selectedDevice == null || selectedDevice.state != "device") {
        return ConsoleConnectionStatus(
            state = ConsoleConnectionState.CHECK_PHONE,
            headline = "Check your phone",
            message = "Unlock your phone or allow debugging, then try again.",
            primaryAction = ConsoleConnectionAction.TRY_AGAIN,
            selectedDevice = selectedDevice?.toConnectionDevice(selectedSerial),
            devices = connectionDevices,
            packageName = session.packageName,
            details = ConsoleConnectionDetails(deviceState = selectedDevice?.state ?: "missing", bridgeState = "not checked"),
        )
    }

    return runCatching { bridge.heartbeat(session.packageName) }
        .fold(
            onSuccess = {
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
                    details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "connected"),
                )
            },
            onFailure = { error ->
                val raw = error.message ?: error::class.java.simpleName
                val unsupported = raw.contains("run-as", ignoreCase = true) &&
                    raw.contains("permission", ignoreCase = true)
                ConsoleConnectionStatus(
                    state = if (unsupported) ConsoleConnectionState.UNSUPPORTED_BUILD else ConsoleConnectionState.OPEN_APP,
                    headline = if (unsupported) "This build cannot connect" else "Open the app",
                    message = if (unsupported) {
                        "Use a debuggable build with FixThis sidekick enabled."
                    } else {
                        "Your phone is connected, but the app is not open."
                    },
                    primaryAction = if (unsupported) ConsoleConnectionAction.TRY_AGAIN else ConsoleConnectionAction.OPEN_APP,
                    selectedDevice = selectedDevice.toConnectionDevice(selectedSerial),
                    devices = connectionDevices,
                    packageName = session.packageName,
                    details = ConsoleConnectionDetails(deviceState = "device", bridgeState = "failed", rawError = raw),
                )
            },
        )
}

suspend fun launchAppForCurrentSession(): ConsoleConnectionStatus {
    val session = currentSession()
    bridge.launchApp(session.packageName)
    return connectionStatus().let { afterLaunch ->
        if (afterLaunch.state == ConsoleConnectionState.READY) {
            afterLaunch
        } else {
            afterLaunch.copy(
                state = ConsoleConnectionState.STARTING,
                headline = "Opening app",
                message = "We're opening the app and connecting.",
                primaryAction = null,
            )
        }
    }
}
```

- [ ] **Step 6: Run service tests and verify GREEN**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.session.FeedbackSessionServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionModels.kt fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt
git commit -m "feat: diagnose console connection state"
```

---

## Task 3: Add Console Connection And Launch APIs

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add failing console API tests**

Add tests to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun connectionApiReturnsSimpleConnectionStatus() {
    val bridge = FakeFixThisBridge()
    bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    val server = consoleServer(bridge = bridge)

    server.use { console ->
        val body = URL("${console.url}/api/connection").readText()
        val json = fixThisJson.parseToJsonElement(body).jsonObject

        assertEquals("READY", json.getValue("state").jsonPrimitive.content)
        assertEquals("Ready", json.getValue("headline").jsonPrimitive.content)
        assertEquals(true, json.getValue("canCapture").jsonPrimitive.boolean)
    }
}

@Test
fun launchAppApiLaunchesSelectedPackageAndReturnsStartingStatus() {
    val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
    bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    val server = consoleServer(bridge = bridge)

    server.use { console ->
        val connection = URL("${console.url}/api/app/launch").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }

        assertEquals(200, connection.responseCode)
        val json = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        assertEquals("STARTING", json.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("io.beyondwin.fixthis.sample"), bridge.launchedPackages)
    }
}
```

Add imports if missing:

```kotlin
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.getValue
```

- [ ] **Step 2: Run console server tests and verify RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: fails because routes and `sendJson(ConsoleConnectionStatus)` do not exist.

- [ ] **Step 3: Implement routes**

In `FeedbackConsoleServer.kt`, add import:

```kotlin
import io.beyondwin.fixthis.mcp.console.ConsoleConnectionStatus
```

Add cases in `handle` before `/api/heartbeat`:

```kotlin
"/api/connection" -> exchange.requireMethod("GET") {
    exchange.sendJson(200, runBlocking { service.connectionStatus() })
}
"/api/app/launch" -> exchange.requireMethod("POST") {
    exchange.sendJson(200, runBlocking { service.launchAppForCurrentSession() })
}
```

Add serializer overload:

```kotlin
private fun HttpExchange.sendJson(statusCode: Int, value: ConsoleConnectionStatus) {
    sendText(statusCode, fixThisJson.encodeToString(ConsoleConnectionStatus.serializer(), value), "application/json; charset=utf-8")
}
```

- [ ] **Step 4: Run console API tests and verify GREEN**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS. If the existing uncommitted prompt evidence assertions are present, they must still pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: expose console connection recovery APIs"
```

---

## Task 4: Add Browser Recovery Card And State Machine

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add failing asset tests**

Add test assertions to an existing console HTML behavior test or a new one:

```kotlin
@Test
fun consoleHasSimpleConnectionRecoveryCard() {
    val html = FeedbackConsoleAssets.html()
    val refreshConnectionBody = javascriptFunctionBody(html, "refreshConnection")
    val renderConnectionBody = javascriptFunctionBody(html, "renderConnection")
    val launchAppBody = javascriptFunctionBody(html, "launchApp")

    assertTrue(html.contains("id=\"connectionCard\""))
    assertTrue(html.contains("id=\"connectionHeadline\""))
    assertTrue(html.contains("id=\"connectionMessage\""))
    assertTrue(html.contains("id=\"connectionPrimaryAction\""))
    assertTrue(html.contains("id=\"connectionDetails\""))
    assertTrue(html.contains("id=\"previewStaleBadge\""))
    assertTrue(html.contains("/api/connection"))
    assertTrue(html.contains("/api/app/launch"))
    assertTrue(refreshConnectionBody.contains("requestJson('/api/connection'"))
    assertTrue(renderConnectionBody.contains("connectionCard.dataset.connectionState"))
    assertTrue(renderConnectionBody.contains("state.connection.hasEverConnected"))
    assertTrue(launchAppBody.contains("requestJson('/api/app/launch'"))
}

@Test
fun connectionDropPreservesDraftWorkAndMarksPreviewStale() {
    val html = FeedbackConsoleAssets.html()
    val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

    assertTrue(applyConnectionBody.contains("pendingFeedbackItems"))
    assertTrue(applyConnectionBody.contains("markPreviewStale"))
    assertTrue(applyConnectionBody.contains("stopLivePreviewPolling"))
    assertTrue(applyConnectionBody.contains("startLivePreviewPolling"))
    assertTrue(applyConnectionBody.contains("state.connection.hasEverConnected = true"))
}
```

- [ ] **Step 2: Run asset tests and verify RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: fails because the new DOM and JS functions do not exist.

- [ ] **Step 3: Add recovery card HTML**

In `index.html`, add this near the top of `<main>` or directly below the topbar so it is visible but not a second app shell:

```html
<section id="connectionCard" class="connection-card" data-connection-state="welcome" aria-live="polite">
  <div class="connection-main">
    <span class="connection-dot" aria-hidden="true"></span>
    <div class="connection-copy">
      <h2 id="connectionHeadline">Connect to your app</h2>
      <p id="connectionMessage">We'll find your phone and open the app for you.</p>
    </div>
  </div>
  <div class="connection-actions">
    <button id="connectionPrimaryAction" class="primary-action" type="button">Start</button>
    <details id="connectionDetails" class="connection-details">
      <summary>Details</summary>
      <pre id="connectionDetailsBody">Waiting for connection check.</pre>
    </details>
  </div>
</section>
```

Add a stale badge inside the preview area, close to `snapshot`:

```html
<span id="previewStaleBadge" class="preview-stale-badge" hidden>Connection paused - showing last preview</span>
```

- [ ] **Step 4: Add recovery card CSS**

Add styles to `styles.css`:

```css
.connection-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 76px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
  background: rgba(16, 18, 20, .92);
}

.connection-card[data-connection-state="ready"] {
  display: none;
}

.connection-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.connection-dot {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: #e6b45a;
  flex: 0 0 auto;
}

.connection-card[data-connection-state="ready"] .connection-dot {
  background: #6fcf97;
}

.connection-card[data-connection-state="check_phone"] .connection-dot,
.connection-card[data-connection-state="unsupported_build"] .connection-dot {
  background: #f26d6d;
}

.connection-copy {
  min-width: 0;
}

.connection-copy h2 {
  margin: 0;
  font-size: 18px;
  line-height: 1.2;
  letter-spacing: 0;
}

.connection-copy p {
  margin: 4px 0 0;
  color: var(--muted);
  line-height: 1.35;
}

.connection-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.primary-action {
  min-height: 38px;
  padding: 0 14px;
  border: 0;
  border-radius: 8px;
  background: #b9dc65;
  color: #0b0d0c;
  font-weight: 800;
}

.primary-action:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.connection-details {
  color: var(--muted);
}

.connection-details pre {
  max-width: 360px;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  color: var(--text);
}

.preview-stale-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 3;
  padding: 7px 10px;
  border-radius: 999px;
  background: rgba(16, 18, 20, .88);
  color: #f1f1f1;
  border: 1px solid var(--line);
  font-size: 12px;
  font-weight: 700;
}
```

If the preview container is not positioned, add `position: relative` to its existing class.

- [ ] **Step 5: Add JS state and actions**

In `app.js`, extend initial state:

```js
const state = {
  session: null,
  preview: null,
  sessionSummaries: [],
  selectedDeviceSerial: null,
  devices: [],
  connection: {
    current: null,
    hasEverConnected: false,
    lastReadyAt: null,
    launchInFlight: false
  }
};
```

Add DOM refs:

```js
const connectionCard = document.getElementById('connectionCard');
const connectionHeadline = document.getElementById('connectionHeadline');
const connectionMessage = document.getElementById('connectionMessage');
const connectionPrimaryAction = document.getElementById('connectionPrimaryAction');
const connectionDetails = document.getElementById('connectionDetails');
const connectionDetailsBody = document.getElementById('connectionDetailsBody');
const previewStaleBadge = document.getElementById('previewStaleBadge');
```

Add functions:

```js
function connectionActionLabel(action) {
  if (action === 'START') return 'Start';
  if (action === 'OPEN_APP') return 'Open app';
  if (action === 'RECONNECT') return 'Reconnect';
  if (action === 'TRY_AGAIN') return 'Try again';
  if (action === 'CHOOSE_DEVICE') return 'Choose device';
  if (action === 'CAPTURE') return 'Capture screen';
  return 'Continue';
}

function userConnectionState(status) {
  if (!status) return 'welcome';
  const rawState = String(status.state || 'WELCOME').toLowerCase();
  if (rawState === 'open_app' && state.connection.hasEverConnected) return 'reconnect';
  return rawState;
}

function connectionDetailsText(status) {
  if (!status) return 'No connection check has run yet.';
  const details = status.details || {};
  return [
    'Device: ' + (status.selectedDevice ? status.selectedDevice.label + ' · ' + status.selectedDevice.state : 'none'),
    'Package: ' + text(status.packageName),
    'Bridge: ' + text(details.bridgeState),
    'Last connected: ' + (state.connection.lastReadyAt ? new Date(state.connection.lastReadyAt).toLocaleTimeString() : '-'),
    'Raw error: ' + text(details.rawError)
  ].join('\\n');
}

function markPreviewStale(stale) {
  previewStaleBadge.hidden = !stale || !state.preview;
}

function applyConnectionStatus(status) {
  state.connection.current = status;
  const viewState = userConnectionState(status);
  if (viewState === 'ready') {
    state.connection.hasEverConnected = true;
    state.connection.lastReadyAt = Date.now();
    markPreviewStale(false);
    startLivePreviewPolling();
  } else {
    stopLivePreviewPolling();
    if (pendingFeedbackItems.length || state.preview) markPreviewStale(true);
  }
  renderConnection(status);
}

function renderConnection(status) {
  const viewState = userConnectionState(status);
  connectionCard.dataset.connectionState = viewState;
  connectionHeadline.textContent = viewState === 'reconnect' ? 'Reconnect' : (status?.headline || 'Connect to your app');
  connectionMessage.textContent = viewState === 'reconnect'
    ? 'The connection was interrupted. Your work is saved.'
    : (status?.message || "We'll find your phone and open the app for you.");
  const action = viewState === 'reconnect' ? 'RECONNECT' : status?.primaryAction;
  connectionPrimaryAction.textContent = connectionActionLabel(action);
  connectionPrimaryAction.disabled = state.connection.launchInFlight || viewState === 'starting';
  connectionPrimaryAction.dataset.connectionAction = action || 'START';
  connectionDetailsBody.textContent = connectionDetailsText(status);
}

async function refreshConnection() {
  const status = await requestJson('/api/connection');
  applyConnectionStatus(status);
  return status;
}

async function launchApp() {
  state.connection.launchInFlight = true;
  renderConnection(state.connection.current);
  try {
    const status = await requestJson('/api/app/launch', { method: 'POST' });
    applyConnectionStatus(status);
    setTimeout(() => refreshConnection().catch(showError), 800);
  } finally {
    state.connection.launchInFlight = false;
    renderConnection(state.connection.current);
  }
}

async function handleConnectionPrimaryAction() {
  const action = connectionPrimaryAction.dataset.connectionAction || 'START';
  if (action === 'START' || action === 'OPEN_APP' || action === 'RECONNECT') {
    await launchApp();
    return;
  }
  if (action === 'TRY_AGAIN') {
    await refreshDevices();
    await refreshConnection();
    return;
  }
  if (action === 'CHOOSE_DEVICE') {
    devicePicker.focus();
    return;
  }
  if (action === 'CAPTURE') {
    await captureScreen();
  }
}
```

Update `sendBridgeHeartbeat` to use the new connection status:

```js
async function sendBridgeHeartbeat() {
  if (!state.selectedDeviceSerial) return;
  await refreshConnection();
}
```

Keep `/api/heartbeat` on the server for compatibility, but new browser UI should update from `/api/connection`.

- [ ] **Step 6: Wire events and initial load**

Add:

```js
connectionPrimaryAction.addEventListener('click', () => handleConnectionPrimaryAction().catch(showError));
```

During startup, call:

```js
await refreshDevices();
await refreshConnection();
```

On device selection success, call:

```js
await refreshConnection();
```

On disconnect, call:

```js
applyConnectionStatus({
  state: 'WELCOME',
  headline: 'Connect to your app',
  message: "We'll find your phone and open the app for you.",
  primaryAction: 'START',
  packageName: state.session?.packageName || '',
  details: { deviceState: 'none', bridgeState: 'not checked' }
});
```

- [ ] **Step 7: Run asset tests and verify GREEN**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html fixthis-mcp/src/main/resources/console/styles.css fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: add resilient console recovery UI"
```

---

## Task 5: Preserve Work During Bridge Failures

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add failing tests for preview and pending preservation**

Add asset assertions:

```kotlin
@Test
fun previewFailureMarksConnectionPausedWithoutClearingDrafts() {
    val html = FeedbackConsoleAssets.html()
    val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")
    val showErrorBody = javascriptFunctionBody(html, "showError")

    assertTrue(refreshPreviewBody.contains("markPreviewStale(true)"))
    assertTrue(refreshPreviewBody.contains("refreshConnection().catch"))
    assertTrue(showErrorBody.contains("Connection paused"))
    assertTrue(html.contains("pendingFeedbackItems = [];"))
}
```

The last assertion documents that drafts are only cleared by explicit draft lifecycle code. Do not add new disconnect-path clearing.

- [ ] **Step 2: Run asset tests and verify RED**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: fails until preview error handling marks stale previews and refreshes connection status.

- [ ] **Step 3: Update preview error handling**

In `refreshPreview`, wrap bridge-dependent fetch:

```js
try {
  const preview = await requestJson('/api/preview');
  state.preview = preview;
  markPreviewStale(false);
  renderPreviewOnly();
  return preview;
} catch (cause) {
  markPreviewStale(true);
  refreshConnection().catch(() => {});
  throw cause;
}
```

In `showError`, map bridge-like errors:

```js
function friendlyErrorMessage(message) {
  const raw = String(message || '');
  if (raw.includes('Bridge closed before sending a response') || raw.includes('timed out') || raw.includes('Could not connect to FixThis bridge')) {
    return 'Connection paused. Your work is saved.';
  }
  if (raw.includes('DEVICE_NOT_AVAILABLE')) {
    return 'Check your phone, then try again.';
  }
  return raw;
}
```

Then use:

```js
error.textContent = friendlyErrorMessage(cause?.message || cause);
```

- [ ] **Step 4: Verify no disconnect path clears pending work**

Search:

```bash
rg -n "pendingFeedbackItems = \\[\\]|addItemsFlow = null|state.preview = null|invalidatePreviewContext" fixthis-mcp/src/main/resources/console/app.js
```

Expected: clearing happens only on explicit user flow boundaries such as cancel, save completion, session/device context change, or new session. It must not happen inside heartbeat or connection failure handling.

- [ ] **Step 5: Run asset tests and verify GREEN**

Run:

```bash
./gradlew :fixthis-mcp:test --tests io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "fix: preserve console work during disconnects"
```

---

## Task 6: Update Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/design-feedback-console-ux.md`
- Modify: `docs/fixthis_prd.md`
- Modify: `docs/troubleshooting.md`

- [ ] **Step 1: Update README console workflow**

In `README.md`, replace the current “Feedback console flow” start with:

```markdown
Feedback console flow:

1. Open the console from `fixthis console --package <applicationId>` or `fixthis_open_feedback_console`.
2. Click `Start`. The console finds the selected/only ready Android device, opens the debug app when possible, and connects to the FixThis sidekick bridge.
3. If the app or device disconnects, use the recovery card: `Open app`, `Reconnect`, or `Try again`. Draft annotations and the last preview remain visible while reconnecting.
4. Use the app normally from the console preview.
5. Click Add when ready to leave feedback on the current screen.
```

- [ ] **Step 2: Update design feedback console UX status**

Append:

```markdown
## Connection Recovery UX

The console now treats connection handling as a simple recovery loop for non-developer users. The main UI shows one state and one primary action: `Connect to your app`, `Ready`, `Open the app`, `Reconnect`, `Choose a device`, `Check your phone`, or `This build cannot connect`.

Technical causes are hidden under Details. The app-side UI remains a minimal MCP status pill; all control stays in the browser console.

When connection drops, the console keeps pending feedback items and the last preview visible, marks the preview as stale, disables live bridge actions, and resumes preview polling after reconnect.
```

- [ ] **Step 3: Update PRD MCP connection UX**

In `docs/fixthis_prd.md` section `8.5 MCP connection UX`, replace the old four-state-only text with:

```markdown
Desktop console control uses a non-developer recovery model. Normal UI exposes:

```text
Connect to your app
Ready
Open the app
Reconnect
Choose a device
Check your phone
This build cannot connect
```

The compact device state remains available in the top bar and Details:

```text
No device
Connecting
Connected
Unavailable
```

The app-side status pill remains limited to `MCP waiting` and `MCP connected`.
```
```

- [ ] **Step 4: Update troubleshooting**

Add:

```markdown
## Browser Console Says Reconnect

`Reconnect` means the console previously reached the FixThis sidekick bridge, but a later heartbeat, preview, or navigation request failed. Common causes are app restart, app reinstall, process death, device sleep, wireless debugging interruption, or the app being backgrounded.

Click `Reconnect`. The console will try to open the app and refresh the bridge session. Draft annotations and the last preview are kept while reconnecting.

Open `Details` for raw diagnostics such as `Bridge closed before sending a response`.
```

- [ ] **Step 5: Run markdown search sanity checks**

Run:

```bash
rg -n "Connect to your app|Reconnect|Open the app|This build cannot connect|MCP waiting|MCP connected" README.md docs
```

Expected: new user-facing states appear in README/PRD/troubleshooting/design docs; app-side pill remains documented as only waiting/connected.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/design-feedback-console-ux.md docs/fixthis_prd.md docs/troubleshooting.md
git commit -m "docs: document resilient console connection UX"
```

---

## Task 7: Full Verification And Manual Smoke

**Files:**
- No code changes unless verification finds defects.

- [ ] **Step 1: Run JVM and Android unit tests**

Run:

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install the sample app**

Run:

```bash
./gradlew :app:installDebug
```

Expected: APK installs on the connected `device` state Android target. If no usable device exists, record `adb devices -l` output and skip only connected-device smoke.

- [ ] **Step 3: Build CLI/MCP distributions**

Run:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Start console with source assets**

Run:

```bash
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

Expected: console prints a localhost URL. Keep only this process running for the smoke, then stop it after verification.

- [ ] **Step 5: Smoke initial connection**

Open the printed URL and verify:

```text
Connect to your app
[Start]
```

Click `Start`.

Expected:

```text
Opening app
Ready
```

The Android app-side pill should transition from `MCP waiting` to `MCP connected` once the console polls connection.

- [ ] **Step 6: Smoke app background/reconnect**

On the phone, press Home or otherwise move the app away.

Expected:

- Browser console keeps the last preview visible.
- Pending draft items are not cleared.
- Console shows either `Open the app` or `Reconnect`.
- Clicking the primary action opens the app and returns to `Ready`.

- [ ] **Step 7: Smoke device interruption**

Temporarily disconnect wireless debugging or unplug the device.

Expected:

- Console shows `Check your phone`.
- Last preview remains visible with stale badge.
- `Details` contains raw device/bridge information.
- Reconnecting the device and clicking `Try again` returns to `Ready` or `Open the app`.

- [ ] **Step 8: Run diff checks**

Run:

```bash
git diff --check
git status --short
```

Expected:

- `git diff --check` has no output.
- `git status --short` shows only intentional final changes before the last commit, or a clean tree after committing.

- [ ] **Step 9: Final commit if verification fixes were needed**

If Task 7 required fixes:

```bash
git add <fixed files>
git commit -m "fix: stabilize resilient console connection flow"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review

- Spec coverage: this plan covers first-run connection, app launch from console, app background/process death, bridge stale/closed response, device offline/unauthorized/missing, multiple devices, non-developer copy, Details diagnostics, and work preservation during disconnects.
- Placeholder scan: no `TBD`, `TODO`, or unbounded “handle edge cases” steps remain. Each code task defines concrete files, test names, methods, and commands.
- Type consistency: DTO names use `ConsoleConnectionStatus`, `ConsoleConnectionState`, `ConsoleConnectionAction`, `ConsoleConnectionDetails`; service methods are `connectionStatus()` and `launchAppForCurrentSession()`; bridge method is `launchApp(packageName)`.
- Existing worktree caution: preserve the current uncommitted prompt evidence assertions in `FeedbackConsoleServerTest.kt` when implementing Tasks 3-5.
