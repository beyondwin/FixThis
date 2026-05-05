# Feedback Console Selection And Agent Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a browser-console workflow for device selection, visible screenshot target selection, draft feedback, and agent handoff history.

**Architecture:** Keep the MCP process as the owner of feedback session state and extend the existing persisted `FeedbackSession` model with delivery state and handoff batches. Add device selection to the CLI bridge layer, expose device/draft/handoff APIs from the local console server, then rebuild the console UI around Select/Navigate modes and readable feedback cards. Android-side navigation remains the existing one-step `back`, `tap`, and `swipe` bridge contract.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Java HTTP server, Android ADB bridge client, local browser HTML/CSS/JavaScript assets, Gradle tests.

---

## Related Documents

- Design: `docs/superpowers/specs/2026-05-05-feedback-console-selection-handoff-design.md`
- Prior V2 design: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- Prior V2 plan: `docs/superpowers/plans/2026-05-04-feedback-workspace-navigation-v2-implementation.md`

## Scope

This plan adds a V2.5 console workflow on top of the implemented V2 workspace:

1. selected ADB device state in the console process
2. readable session, screen, draft item, and sent batch labels
3. `Select` and `Navigate` modes for snapshot interaction
4. component click selection and custom area drag selection
5. draft feedback item creation and clearing
6. sent handoff batch history
7. MCP-readable draft and sent feedback output

This plan excludes arbitrary text input into the Android app, scripted exploration,
multi-step automation, cloud sync, Android network services, DTA network mocking,
and direct calls to external AI APIs.

## Required Baseline

Before Task 1, workers must verify the repository state:

```bash
git status --short --branch
git branch --show-current
git rev-parse HEAD
rg --files -g 'AGENTS.md' -g 'CLAUDE.md' -g '*agent*guide*' -g '*instructions*'
```

If instruction files are listed, read them before editing. Existing unrelated
untracked files must not be staged or modified.

## File Map

Create:

- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackHandoffModels.kt`: delivery state and handoff batch serializable models.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleDeviceModels.kt`: console device API response/request models.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleItemModels.kt`: console add-item, clear-draft, and handoff request models.

Modify:

- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Adb.kt`: parse `adb devices -l`, carry metadata, and support serial-scoped commands.
- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`: expose devices, selected serial, and serial-scoped bridge requests.
- `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`: selected-device and device-list coverage.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`: add delivery fields to feedback items and handoff history to sessions.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt`: add draft and handoff counts.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`: assign item numbers, clear draft, and create handoff batches.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`: validate selected targets and expose draft/handoff operations.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`: group draft and sent feedback for agent-readable output.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`: add bridge device methods and summary/read output updates.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`: add device, draft clear, handoff, and richer item APIs.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`: redesign console HTML/CSS/JS around device picker, Select/Navigate modes, overlay selection, draft queue, and sent history.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`: delivery and handoff persistence behavior.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`: selection validation and handoff service behavior.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`: fake device list and selection support.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`: formatter grouping for draft and sent handoff output.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`: new console APIs and asset smoke coverage.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`: MCP read/list output assertions.
- `README.md`
- `docs/mcp.md`
- `docs/output-schema.md`
- `docs/privacy.md`
- `docs/troubleshooting.md`

## Task 1: Baseline Verification

**Files:**

- No source edits.

- [x] **Step 1: Check branch, status, and instructions**

Run:

```bash
git status --short --branch
git branch --show-current
git rev-parse HEAD
rg --files -g 'AGENTS.md' -g 'CLAUDE.md' -g '*agent*guide*' -g '*instructions*'
```

Expected: branch and unrelated changes are understood. If instruction files are
listed, read them before Task 2.

- [x] **Step 2: Run baseline tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-cli:test :pointpatch-mcp:test
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest
```

Expected: both commands PASS.

- [x] **Step 3: Commit only if local process requires it**

If no files changed, do not commit. Record baseline output in the task
checkpoint.

## Task 2: Device-Aware ADB And Bridge Client

**Files:**

- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Adb.kt`
- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Modify: `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`

- [x] **Step 1: Add failing selected-device tests**

Add tests to `BridgeClientTest`:

```kotlin
@Test
fun parsesDeviceMetadataFromAdbDevicesLongOutput() {
    val adb = FakeAdbFacade(
        sessionJson = sessionJson(protocol = "1.0"),
        devices = listOf(
            AdbDevice(
                serial = "adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp",
                state = "device",
                model = "SM_G986N",
                product = "y2qksx",
                deviceName = "y2q",
            ),
            AdbDevice(
                serial = "emulator-5554",
                state = "offline",
                model = "sdk_gphone64",
                product = "sdk_phone64",
                deviceName = "emu64",
            ),
        ),
    )
    val client = BridgeClient(adb = adb, projectRoot = temporaryFolder.newFolder())

    val devices = client.devices()

    assertEquals(2, devices.size)
    assertEquals("SM_G986N", devices.first().model)
    assertEquals("offline", devices[1].state)
}

@Test
fun selectedDeviceSerialScopesBridgeAdbCommands() = runBlocking {
    val adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0"))
    val socket = CapturingBridgeSocket(
        responsePayload = """
            {
              "id": "req_1",
              "ok": true,
              "result": {
                "bridgeProtocolVersion": "1.0",
                "activity": "MainActivity"
              }
            }
        """.trimIndent(),
    )
    val client = BridgeClient(
        adb = adb,
        projectRoot = temporaryFolder.newFolder(),
        portAllocator = { 34567 },
        socketConnector = { socket },
    )

    client.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
    client.request("io.github.pointpatch.sample", "status")

    assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", client.selectedDeviceSerial())
    assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.runAsSerials)
    assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.forwardSerials)
    assertEquals(listOf("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"), adb.removeForwardSerials)
}

@Test
fun disconnectDeviceClearsOnlyClientSelection() {
    val client = BridgeClient(
        adb = FakeAdbFacade(sessionJson = sessionJson(protocol = "1.0")),
        projectRoot = temporaryFolder.newFolder(),
    )

    client.selectDevice("device-1")
    client.disconnectDevice()

    assertEquals(null, client.selectedDeviceSerial())
}
```

Update `FakeAdbFacade` in the same test file so it can record serial-scoped
calls:

```kotlin
private class FakeAdbFacade(
    private val sessionJson: String,
    private val devices: List<AdbDevice> = listOf(AdbDevice("emulator-5554", "device")),
    private val selectedSerial: String? = null,
) : AdbFacade {
    val forwarded = mutableListOf<Pair<Int, String>>()
    val removedForwards = mutableListOf<Int>()
    val pulled = mutableListOf<Pair<String, File>>()
    val runAsSerials = mutableListOf<String?>()
    val forwardSerials = mutableListOf<String?>()
    val removeForwardSerials = mutableListOf<String?>()

    override fun devices(): List<AdbDevice> = devices

    override fun forDevice(serial: String?): AdbFacade =
        FakeAdbFacade(sessionJson = sessionJson, devices = devices, selectedSerial = serial).also { child ->
            child.forwarded += forwarded
            child.removedForwards += removedForwards
            child.pulled += pulled
            child.runAsSerials += runAsSerials
            child.forwardSerials += forwardSerials
            child.removeForwardSerials += removeForwardSerials
        }

    override fun runAsCat(packageName: String, path: String): String {
        runAsSerials += selectedSerial
        assertEquals("io.github.pointpatch.sample", packageName)
        assertEquals("files/pointpatch/session.json", path)
        return sessionJson
    }

    override fun forward(localPort: Int, socketAddress: String) {
        forwardSerials += selectedSerial
        forwarded += localPort to socketAddress
    }

    override fun removeForward(localPort: Int) {
        removeForwardSerials += selectedSerial
        removedForwards += localPort
    }

    override fun pull(androidPath: String, destination: File) {
        pulled += androidPath to destination
    }
}
```

- [x] **Step 2: Run device tests to verify failure**

Run:

```bash
./gradlew :pointpatch-cli:test --tests io.github.pointpatch.cli.BridgeClientTest
```

Expected: FAIL because `AdbDevice` lacks metadata, `AdbFacade.forDevice` is not
defined, and `BridgeClient` has no selected-device methods.

- [x] **Step 3: Extend ADB models and serial scoping**

Change `Adb.kt`:

```kotlin
data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val deviceName: String? = null,
)
```

Add a default `forDevice` method to `AdbFacade`:

```kotlin
interface AdbFacade {
    fun devices(): List<AdbDevice>
    fun forDevice(serial: String?): AdbFacade = this
    fun runAsCat(packageName: String, path: String): String
    fun forward(localPort: Int, socketAddress: String)
    fun removeForward(localPort: Int)
    fun pull(androidPath: String, destination: File)
}
```

Update `Adb` constructor and command scoping:

```kotlin
class Adb(
    private val adbExecutable: String = defaultAdbExecutable(),
    private val runner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val selectedSerial: String? = null,
) : AdbFacade {
    override fun forDevice(serial: String?): AdbFacade =
        Adb(adbExecutable = adbExecutable, runner = runner, selectedSerial = serial?.takeIf { it.isNotBlank() })

    override fun devices(): List<AdbDevice> {
        val result = checkedRun(listOf("devices", "-l"), includeSelectedSerial = false)
        return result.stdout.lineSequence()
            .drop(1)
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line -> parseDeviceLine(line) }
            .toList()
    }

    private fun checkedRun(arguments: List<String>, includeSelectedSerial: Boolean = true): AdbResult {
        val serialArgs = if (includeSelectedSerial && !selectedSerial.isNullOrBlank()) listOf("-s", selectedSerial) else emptyList()
        val command = listOf(adbExecutable) + serialArgs + arguments
        val result = runner.run(command)
        if (result.exitCode != 0) {
            throw AdbException(command, result)
        }
        return result
    }

    private fun parseDeviceLine(line: String): AdbDevice? {
        val parts = line.split(Regex("\\s+"))
        val serial = parts.getOrNull(0) ?: return null
        val state = parts.getOrNull(1) ?: return null
        val metadata = parts.drop(2).mapNotNull { token ->
            val pieces = token.split(":", limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        return AdbDevice(
            serial = serial,
            state = state,
            model = metadata["model"],
            product = metadata["product"],
            deviceName = metadata["device"],
        )
    }
}
```

- [x] **Step 4: Add selected device methods to BridgeClient**

In `BridgeClient.kt`, add selected-device state:

```kotlin
@Volatile
private var selectedDeviceSerial: String? = null

fun devices(): List<AdbDevice> = adb.devices()

fun selectDevice(serial: String) {
    require(serial.isNotBlank()) { "Device serial must not be blank" }
    selectedDeviceSerial = serial
}

fun disconnectDevice() {
    selectedDeviceSerial = null
}

fun selectedDeviceSerial(): String? = selectedDeviceSerial

private fun activeAdb(): AdbFacade =
    adb.forDevice(selectedDeviceSerial)
```

Use `activeAdb()` for every request-scoped bridge operation:

```kotlin
val requestAdb = activeAdb()
val activeRequest = ActiveBridgeRequest(requestAdb)
ensureDeviceConnected(requestAdb)
val session = readSidekickSession(requestAdb, packageName)
```

Change helpers to accept the scoped facade:

```kotlin
private fun ensureDeviceConnected(adb: AdbFacade) {
    val devices = adb.devices()
    val selected = selectedDeviceSerial
    if (selected == null) {
        if (devices.none { it.state == "device" }) {
            throw NoDeviceException("No connected Android device or emulator found")
        }
        return
    }
    val device = devices.firstOrNull { it.serial == selected }
        ?: throw NoDeviceException("Selected Android device is not connected: $selected")
    if (device.state != "device") {
        throw NoDeviceException("Selected Android device is not ready: $selected (${device.state})")
    }
}

private fun readSidekickSession(adb: AdbFacade, packageName: String): SidekickSession =
    runCatching {
        pointPatchJson.decodeFromString(
            SidekickSession.serializer(),
            adb.runAsCat(packageName, SessionPath),
        )
    }.getOrElse { error ->
        throw BridgeConnectionException(
            "Could not read sidekick session via adb shell run-as $packageName cat $SessionPath: ${error.message}",
        )
    }
```

- [x] **Step 5: Run CLI bridge tests**

Run:

```bash
./gradlew :pointpatch-cli:test --tests io.github.pointpatch.cli.BridgeClientTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Adb.kt pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt
git commit -m "cli: route bridge requests to selected device"
```

## Task 3: Feedback Delivery And Handoff Models

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackHandoffModels.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Add failing delivery and handoff store tests**

Add tests to `FeedbackSessionStoreTest`:

```kotlin
@Test
fun feedbackSessionRoundTripsDeliveryAndHandoffHistory() {
    val session = FeedbackSession(
        sessionId = "session-1",
        packageName = "io.github.pointpatch.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 20L,
        items = listOf(
            FeedbackItem(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 11L,
                updatedAtEpochMillis = 12L,
                target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
                comment = "Fix spacing",
                sequenceNumber = 1,
                delivery = FeedbackDelivery.SENT,
                handoffBatchId = "batch-1",
                sentAtEpochMillis = 15L,
            ),
        ),
        handoffBatches = listOf(
            FeedbackHandoffBatch(
                batchId = "batch-1",
                sequenceNumber = 1,
                createdAtEpochMillis = 15L,
                itemIds = listOf("item-1"),
                markdownSnapshot = "Batch markdown",
            ),
        ),
    )

    val encoded = pointPatchJson.encodeToString(FeedbackSession.serializer(), session)
    val decoded = pointPatchJson.decodeFromString(FeedbackSession.serializer(), encoded)

    assertEquals(session, decoded)
    assertTrue(encoded.contains("\"delivery\":\"sent\""))
    assertTrue(encoded.contains("\"handoffBatches\""))
}

@Test
fun storeAssignsItemSequenceNumbersAndSendsDraftBatch() {
    val clock = FakeClock(100L)
    val ids = FakeIds("session-1", "screen-1", "item-1", "item-2", "batch-1")
    val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
    val session = store.openSession("io.github.pointpatch.sample", "/repo")
    val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
    store.addItem(
        session.sessionId,
        FeedbackItem(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
            comment = "First",
        ),
    )
    store.addItem(
        session.sessionId,
        FeedbackItem(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
            comment = "Second",
        ),
    )

    val sent = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "markdown")

    assertEquals(FeedbackSessionStatus.READY_FOR_AGENT, sent.status)
    assertEquals(listOf(1, 2), sent.items.map { it.sequenceNumber })
    assertEquals(listOf(FeedbackDelivery.SENT, FeedbackDelivery.SENT), sent.items.map { it.delivery })
    assertEquals(listOf("batch-1", "batch-1"), sent.items.map { it.handoffBatchId })
    assertEquals(1, sent.handoffBatches.single().sequenceNumber)
    assertEquals(listOf("item-1", "item-2"), sent.handoffBatches.single().itemIds)
}

@Test
fun clearDraftItemsKeepsSentHistory() {
    val clock = FakeClock(100L)
    val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2")
    val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
    val session = store.openSession("io.github.pointpatch.sample", "/repo")
    val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
    store.addItem(session.sessionId, FeedbackItem("pending", screen.screenId, 0L, 0L, FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)), comment = "Sent"))
    store.sendDraftToAgent(session.sessionId, markdownSnapshot = "sent")
    store.addItem(session.sessionId, FeedbackItem("pending", screen.screenId, 0L, 0L, FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)), comment = "Draft"))

    val cleared = store.clearDraftItems(session.sessionId)

    assertEquals(listOf("Sent"), cleared.items.map { it.comment })
    assertEquals(FeedbackDelivery.SENT, cleared.items.single().delivery)
    assertEquals(1, cleared.handoffBatches.size)
}
```

- [x] **Step 2: Run store tests to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: FAIL because delivery and handoff models and store methods do not exist.

- [x] **Step 3: Add delivery and handoff models**

Create `FeedbackHandoffModels.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackDelivery {
    @SerialName("draft")
    DRAFT,

    @SerialName("sent")
    SENT,
}

@Serializable
data class FeedbackHandoffBatch(
    val batchId: String,
    val sequenceNumber: Int,
    val createdAtEpochMillis: Long,
    val itemIds: List<String>,
    val markdownSnapshot: String? = null,
)
```

Update `FeedbackSessionModels.kt`:

```kotlin
@Serializable
data class FeedbackSession(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val screens: List<CapturedScreen> = emptyList(),
    val items: List<FeedbackItem> = emptyList(),
    val handoffBatches: List<FeedbackHandoffBatch> = emptyList(),
    val status: FeedbackSessionStatus = FeedbackSessionStatus.ACTIVE,
)
```

Update `FeedbackItem` fields:

```kotlin
@Serializable
data class FeedbackItem(
    val itemId: String,
    val screenId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: FeedbackTarget,
    val selectedNode: PointPatchNode? = null,
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: FeedbackScreenshot? = null,
    val comment: String,
    val sequenceNumber: Int? = null,
    val delivery: FeedbackDelivery = FeedbackDelivery.DRAFT,
    val handoffBatchId: String? = null,
    val sentAtEpochMillis: Long? = null,
    val status: FeedbackItemStatus = FeedbackItemStatus.OPEN,
    val agentSummary: String? = null,
)
```

- [x] **Step 4: Extend summaries with draft and batch counts**

Update `FeedbackSessionSummary.kt`:

```kotlin
@Serializable
data class FeedbackSessionSummary(
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: FeedbackSessionStatus,
    val screensCount: Int,
    val itemsCount: Int,
    val unresolvedItemsCount: Int,
    val draftItemsCount: Int = 0,
    val sentBatchesCount: Int = 0,
)
```

Update the summary factory so `draftItemsCount` counts `FeedbackDelivery.DRAFT`
items and `sentBatchesCount` is `session.handoffBatches.size`.

- [x] **Step 5: Implement store handoff mutations**

Add to `FeedbackSessionStore.kt`:

```kotlin
fun clearDraftItems(sessionId: String): FeedbackSession =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val updated = session.copy(
            items = session.items.filter { it.delivery != FeedbackDelivery.DRAFT },
            updatedAtEpochMillis = clock(),
        )
        commitSessionMutation(session, updated)
    }

fun sendDraftToAgent(sessionId: String, markdownSnapshot: String?): FeedbackSession =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val draftItems = session.items.filter { it.delivery == FeedbackDelivery.DRAFT }
        if (draftItems.isEmpty()) {
            throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No draft feedback items to send")
        }
        val now = clock()
        val batch = FeedbackHandoffBatch(
            batchId = idGenerator(),
            sequenceNumber = session.handoffBatches.size + 1,
            createdAtEpochMillis = now,
            itemIds = draftItems.map { it.itemId },
            markdownSnapshot = markdownSnapshot,
        )
        val updatedItems = session.items.map { item ->
            if (item.delivery == FeedbackDelivery.DRAFT) {
                item.copy(
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = batch.batchId,
                    sentAtEpochMillis = now,
                    status = FeedbackItemStatus.READY,
                    updatedAtEpochMillis = now,
                )
            } else {
                item
            }
        }
        val updated = session.copy(
            items = updatedItems,
            handoffBatches = session.handoffBatches + batch,
            status = FeedbackSessionStatus.READY_FOR_AGENT,
            updatedAtEpochMillis = now,
        )
        commitSessionMutation(session, updated)
    }
```

Update `addItem` so it assigns item sequence numbers:

```kotlin
val created = item.copy(
    itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
    createdAtEpochMillis = now,
    updatedAtEpochMillis = now,
    sequenceNumber = item.sequenceNumber ?: session.items.size + 1,
    delivery = item.delivery,
)
```

- [x] **Step 6: Run store tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackHandoffModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "mcp: model feedback handoff batches"
```

## Task 4: Selection-Aware Feedback Service

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleItemModels.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`

- [x] **Step 1: Add failing service tests for node and area feedback**

Add tests to `FeedbackSessionServiceTest`:

```kotlin
@Test
fun addSelectedNodeFeedbackStoresSelectedNode() {
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(),
        store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
        projectRoot = "/repo",
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val session = service.openSession(null, newSession = true)
    val node = PointPatchNode(
        uid = "compose:0:merged:10",
        composeNodeId = 10,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = PointPatchRect(10f, 20f, 110f, 70f),
        text = listOf("Pay now"),
    )
    val screen = service.addCapturedScreenForTest(
        session.sessionId,
        CapturedScreen(
            screenId = "screen-1",
            capturedAtEpochMillis = 100L,
            displayName = "Checkout",
            roots = listOf(FeedbackScreenRoot(0, PointPatchRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
            screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
        ),
    )

    val item = service.addFeedbackItem(
        sessionId = session.sessionId,
        screenId = screen.screenId,
        targetType = FeedbackTargetType.NODE,
        bounds = node.boundsInWindow,
        nodeUid = node.uid,
        comment = "Button copy is unclear",
    )

    assertEquals(FeedbackTarget.Node(node.uid, node.boundsInWindow), item.target)
    assertEquals(node, item.selectedNode)
    assertEquals(FeedbackDelivery.DRAFT, item.delivery)
    assertEquals(1, item.sequenceNumber)
}

@Test
fun addCustomAreaFeedbackRejectsBoundsOutsideScreenshot() {
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(),
        store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
        projectRoot = "/repo",
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val session = service.openSession(null, newSession = true)
    service.addCapturedScreenForTest(
        session.sessionId,
        CapturedScreen(
            screenId = "screen-1",
            capturedAtEpochMillis = 100L,
            displayName = "Checkout",
            screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
        ),
    )

    val error = assertFailsWith<IllegalArgumentException> {
        service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = "screen-1",
            targetType = FeedbackTargetType.AREA,
            bounds = PointPatchRect(-1f, 0f, 10f, 10f),
            nodeUid = null,
            comment = "Bad bounds",
        )
    }

    assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
}
```

Add this private helper to `FeedbackSessionServiceTest` so tests can seed a
captured screen without changing production visibility:

```kotlin
private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: CapturedScreen): CapturedScreen =
    javaClass.getDeclaredField("store").let { field ->
        field.isAccessible = true
        (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
    }
```

- [x] **Step 2: Run service tests to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: FAIL because `FeedbackTargetType` and `addFeedbackItem` do not exist.

- [x] **Step 3: Add console item request models**

Create `FeedbackConsoleItemModels.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackTargetType {
    @SerialName("area")
    AREA,

    @SerialName("node")
    NODE,
}

@Serializable
data class AddFeedbackItemRequest(
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
)
```

- [x] **Step 4: Implement service selection validation**

Add to `FeedbackSessionService.kt`:

```kotlin
fun addFeedbackItem(
    sessionId: String,
    screenId: String,
    targetType: FeedbackTargetType,
    bounds: PointPatchRect,
    nodeUid: String?,
    comment: String,
): FeedbackItem {
    require(comment.isNotBlank()) { "Feedback comment must not be blank" }
    validateFinitePositiveBounds(bounds)
    val session = store.getSession(sessionId)
    val screen = session.screens.firstOrNull { it.screenId == screenId }
        ?: throw FeedbackSessionException("SCREEN_NOT_FOUND: Unknown screen: $screenId")
    validateBoundsInsideScreenshot(screen, bounds)
    val selectedNode = if (targetType == FeedbackTargetType.NODE) {
        val uid = nodeUid?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Node feedback requires nodeUid")
        screen.roots.asSequence()
            .flatMap { root -> (root.mergedNodes + root.unmergedNodes).asSequence() }
            .firstOrNull { node -> node.uid == uid }
            ?: throw IllegalArgumentException("Selected node does not exist on screen: $uid")
    } else {
        null
    }
    val target = when (targetType) {
        FeedbackTargetType.AREA -> FeedbackTarget.Area(bounds)
        FeedbackTargetType.NODE -> FeedbackTarget.Node(
            nodeUid = selectedNode!!.uid,
            boundsInWindow = selectedNode.boundsInWindow,
        )
    }
    return store.addItem(
        sessionId,
        FeedbackItem(
            itemId = "pending",
            screenId = screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = target,
            selectedNode = selectedNode,
            comment = comment,
            status = FeedbackItemStatus.OPEN,
        ),
    )
}

fun clearDraftItems(sessionId: String): FeedbackSession =
    store.clearDraftItems(sessionId)

fun sendDraftToAgent(sessionId: String): FeedbackSession =
    store.sendDraftToAgent(sessionId, markdownSnapshot = FeedbackQueueFormatter.toMarkdown(store.getSession(sessionId)))
```

Add validation helpers:

```kotlin
private fun validateFinitePositiveBounds(bounds: PointPatchRect) {
    val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
    require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
    require(bounds.right > bounds.left && bounds.bottom > bounds.top) { "Selection bounds must have positive size" }
}

private fun validateBoundsInsideScreenshot(screen: CapturedScreen, bounds: PointPatchRect) {
    val width = screen.screenshot?.width?.toFloat() ?: return
    val height = screen.screenshot?.height?.toFloat() ?: return
    require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
        "Selection bounds must be inside the screenshot"
    }
}
```

Keep `addAreaFeedback` for compatibility, but implement it through the store as
it does today.

- [x] **Step 5: Run service tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleItemModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt
git commit -m "mcp: add selection-aware feedback items"
```

## Task 5: Console Device, Draft, And Handoff APIs

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleDeviceModels.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing console API tests**

Add tests to `FeedbackConsoleServerTest`:

```kotlin
@Test
fun devicesApiListsAndSelectsActiveDevice() {
    val bridge = FakePointPatchBridge()
    val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        val devices = URL("${server.url}/api/devices").readText()
        assertTrue(devices.contains("SM_G986N"))
        assertTrue(devices.contains("adb-R3CN60LXW3L"))

        val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
        select.requestMethod = "POST"
        select.doOutput = true
        select.setRequestProperty("Content-Type", "application/json")
        select.outputStream.use { it.write("""{"serial":"adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"}""".toByteArray()) }

        assertEquals(200, select.responseCode)
        assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", bridge.selectedDeviceSerial)
    } finally {
        server.stop()
    }
}

@Test
fun agentHandoffApiSendsDraftAndClearsDraftList() {
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(),
        store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next),
        projectRoot = "/repo",
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val session = service.openSession(null, newSession = true)
    val screen = service.captureFakeScreenForTest(session.sessionId)
    service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(0f, 0f, 10f, 10f), "Fix it")
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        val handoff = URL("${server.url}/api/agent-handoffs").openConnection() as HttpURLConnection
        handoff.requestMethod = "POST"
        handoff.doOutput = true
        handoff.setRequestProperty("Content-Type", "application/json")
        handoff.outputStream.use { it.write("{}".toByteArray()) }

        assertEquals(200, handoff.responseCode)
        val body = handoff.inputStream.bufferedReader().readText()
        assertTrue(body.contains("\"handoffBatches\""))
        assertTrue(body.contains("\"delivery\":\"sent\""))
    } finally {
        server.stop()
    }
}

@Test
fun clearDraftApiKeepsSentItems() {
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(),
        store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2").next),
        projectRoot = "/repo",
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val session = service.openSession(null, newSession = true)
    val screen = service.captureFakeScreenForTest(session.sessionId)
    service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(0f, 0f, 10f, 10f), "Sent")
    service.sendDraftToAgent(session.sessionId)
    service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(10f, 10f, 20f, 20f), "Draft")
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        val clear = URL("${server.url}/api/items/draft").openConnection() as HttpURLConnection
        clear.requestMethod = "DELETE"

        assertEquals(200, clear.responseCode)
        val body = clear.inputStream.bufferedReader().readText()
        assertTrue(body.contains("Sent"))
        assertFalse(body.contains("Draft"))
    } finally {
        server.stop()
    }
}
```

Use a local test helper:

```kotlin
private fun FeedbackSessionService.captureFakeScreenForTest(sessionId: String): CapturedScreen =
    runBlocking { captureScreen(sessionId) }
```

- [x] **Step 2: Run console tests to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: FAIL because device, draft clear, and handoff APIs do not exist.

- [x] **Step 3: Add device console models and bridge methods**

Create `FeedbackConsoleDeviceModels.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import io.github.pointpatch.cli.AdbDevice
import kotlinx.serialization.Serializable

@Serializable
data class ConsoleDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val deviceName: String? = null,
    val selected: Boolean = false,
)

@Serializable
data class ConsoleDeviceList(
    val devices: List<ConsoleDevice>,
    val selectedSerial: String? = null,
)

@Serializable
data class SelectDeviceRequest(
    val serial: String,
)

fun AdbDevice.toConsoleDevice(selectedSerial: String?): ConsoleDevice =
    ConsoleDevice(
        serial = serial,
        state = state,
        model = model,
        product = product,
        deviceName = deviceName,
        selected = serial == selectedSerial,
    )
```

Extend `PointPatchBridge` and `CliPointPatchBridge` in `PointPatchTools.kt`:

```kotlin
interface PointPatchBridge {
    fun resolvePackageName(packageOverride: String?): String
    fun devices(): List<AdbDevice> = emptyList()
    fun selectedDeviceSerial(): String? = null
    fun selectDevice(serial: String) = Unit
    fun disconnectDevice() = Unit
    suspend fun status(packageName: String): JsonObject
    suspend fun inspectCurrentScreen(packageName: String): JsonObject
    suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject
    suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject
    suspend fun captureScreenSnapshot(packageName: String, sessionId: String? = null, screenId: String? = null, destinationDirectory: File? = null): JsonObject
    suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject
}

class CliPointPatchBridge(private val client: BridgeClient) : PointPatchBridge {
    override fun devices(): List<AdbDevice> = client.devices()
    override fun selectedDeviceSerial(): String? = client.selectedDeviceSerial()
    override fun selectDevice(serial: String) = client.selectDevice(serial)
    override fun disconnectDevice() = client.disconnectDevice()
}
```

Update `FakePointPatchBridge`:

```kotlin
var selectedDeviceSerial: String? = null
    private set

override fun devices(): List<AdbDevice> =
    listOf(
        AdbDevice(
            serial = "adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp",
            state = "device",
            model = "SM_G986N",
            product = "y2qksx",
            deviceName = "y2q",
        ),
    )

override fun selectedDeviceSerial(): String? = selectedDeviceSerial

override fun selectDevice(serial: String) {
    selectedDeviceSerial = serial
}

override fun disconnectDevice() {
    selectedDeviceSerial = null
}
```

- [x] **Step 4: Add console server routes**

In `FeedbackConsoleServer.handle`, add:

```kotlin
"/api/devices" -> exchange.requireMethod("GET") {
    val selectedSerial = service.selectedDeviceSerial()
    exchange.sendJson(
        200,
        ConsoleDeviceList(
            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
            selectedSerial = selectedSerial,
        ),
    )
}
"/api/device/select" -> exchange.requireMethod("POST") {
    val request = exchange.decodeSelectDeviceBody()
    service.selectDevice(request.serial)
    val selectedSerial = service.selectedDeviceSerial()
    exchange.sendJson(
        200,
        ConsoleDeviceList(
            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
            selectedSerial = selectedSerial,
        ),
    )
}
"/api/device/disconnect" -> exchange.requireMethod("POST") {
    service.disconnectDevice()
    exchange.sendJson(200, ConsoleDeviceList(devices = service.devices().map { it.toConsoleDevice(null) }))
}
"/api/items/draft" -> exchange.requireMethod("DELETE") {
    exchange.sendJson(200, service.clearDraftItems(service.currentSession().sessionId))
}
"/api/agent-handoffs" -> exchange.requireMethod("POST") {
    exchange.sendJson(200, service.sendDraftToAgent(service.currentSession().sessionId))
}
```

Add decode/send helpers:

```kotlin
private fun HttpExchange.decodeSelectDeviceBody(): SelectDeviceRequest {
    val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
    return runCatching {
        pointPatchJson.decodeFromString(SelectDeviceRequest.serializer(), body)
    }.getOrElse { error ->
        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
    }
}

private fun HttpExchange.sendJson(statusCode: Int, value: ConsoleDeviceList) {
    sendText(statusCode, pointPatchJson.encodeToString(ConsoleDeviceList.serializer(), value), "application/json; charset=utf-8")
}
```

Add pass-through methods to `FeedbackSessionService`:

```kotlin
fun devices(): List<AdbDevice> = bridge.devices()
fun selectedDeviceSerial(): String? = bridge.selectedDeviceSerial()
fun selectDevice(serial: String) = bridge.selectDevice(serial)
fun disconnectDevice() = bridge.disconnectDevice()
```

Map `NO_DRAFT_FEEDBACK` to HTTP 409 in `toConsoleHttpException`.

- [x] **Step 5: Update `/api/items` to use selection request**

Replace the existing `/api/items` branch body:

```kotlin
val request = exchange.decodeAddFeedbackItemBody()
val session = service.currentSession()
val item = service.addFeedbackItem(
    sessionId = session.sessionId,
    screenId = request.screenId,
    targetType = request.targetType,
    bounds = request.bounds,
    nodeUid = request.nodeUid,
    comment = request.comment,
)
exchange.sendJson(200, item)
```

Add:

```kotlin
private fun HttpExchange.decodeAddFeedbackItemBody(): AddFeedbackItemRequest {
    val body = requestBody.use { input -> input.readBytes().toString(Charsets.UTF_8) }
    return runCatching {
        pointPatchJson.decodeFromString(AddFeedbackItemRequest.serializer(), body)
    }.getOrElse { error ->
        throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
    }
}
```

- [x] **Step 6: Run console tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleDeviceModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleItemModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: add console handoff and device APIs"
```

## Task 6: Agent-Readable Queue Output

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [x] **Step 1: Add failing formatter and MCP output tests**

Create `FeedbackQueueFormatterTest.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
import kotlin.test.assertTrue

class FeedbackQueueFormatterTest {
    @Test
    fun markdownGroupsDraftAndSentHandoffHistory() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(CapturedScreen("screen-1", 2L, displayName = "Checkout")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                    comment = "Draft spacing",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.DRAFT,
                ),
                FeedbackItem(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
                    comment = "Sent copy",
                    sequenceNumber = 2,
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
                    sentAtEpochMillis = 5L,
                    status = FeedbackItemStatus.READY,
                ),
            ),
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-1",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 5L,
                    itemIds = listOf("item-2"),
                    markdownSnapshot = "snapshot",
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("## Draft"))
        assertTrue(markdown.contains("Draft spacing"))
        assertTrue(markdown.contains("## Sent History"))
        assertTrue(markdown.contains("Batch #1"))
        assertTrue(markdown.contains("Sent copy"))
        assertTrue(markdown.contains("Delivery: `sent`"))
        assertTrue(markdown.contains("Screen 1 - Checkout"))
    }
}
```

Add a focused protocol assertion to `McpProtocolTest`:

```kotlin
@Test
fun listFeedbackIncludesDraftAndSentCounts() = runBlocking {
    val tools = PointPatchTools(
        bridge = FakePointPatchBridge(),
        projectRoot = createTempDir(prefix = "pointpatch-handoff-list-"),
        defaultPackageName = "io.github.pointpatch.sample",
    )
    tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true))

    val result = tools.call("pointpatch_list_feedback", JsonObject(emptyMap())).firstJsonContent()

    assertTrue(result.toString().contains("draftItemsCount"))
    assertTrue(result.toString().contains("sentBatchesCount"))
}
```

- [x] **Step 2: Run formatter and MCP tests to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest --tests io.github.pointpatch.mcp.McpProtocolTest.listFeedbackIncludesDraftAndSentCounts
```

Expected: FAIL because formatter output does not group sent history and list
summaries do not include draft/sent counts.

- [x] **Step 3: Update markdown formatter**

Change `FeedbackQueueFormatter.toMarkdown` to include draft and sent history:

```kotlin
appendLine("## Draft")
appendLine()
val draftItems = session.items.filter { it.delivery == FeedbackDelivery.DRAFT }
if (draftItems.isEmpty()) {
    appendLine("No draft feedback items.")
    appendLine()
} else {
    draftItems.forEach { item -> appendFeedbackItem(session, item) }
}

appendLine("## Sent History")
appendLine()
if (session.handoffBatches.isEmpty()) {
    appendLine("No sent handoff batches.")
    appendLine()
} else {
    session.handoffBatches.sortedBy { it.sequenceNumber }.forEach { batch ->
        appendLine("### Batch #${batch.sequenceNumber}")
        appendLine()
        appendLine("- Batch ID: `${batch.batchId}`")
        appendLine("- Sent At: `${batch.createdAtEpochMillis}`")
        appendLine("- Item Count: `${batch.itemIds.size}`")
        appendLine()
        batch.itemIds.mapNotNull { itemId -> session.items.firstOrNull { it.itemId == itemId } }
            .forEach { item -> appendFeedbackItem(session, item) }
    }
}
```

Add helper functions:

```kotlin
private fun StringBuilder.appendFeedbackItem(session: FeedbackSession, item: FeedbackItem) {
    val number = item.sequenceNumber?.let { "#$it " }.orEmpty()
    appendLine("### $number${item.comment.lineSequence().firstOrNull().orEmpty().ifBlank { "(No comment)" }}")
    appendLine()
    appendLine("- Item ID: `${item.itemId}`")
    appendLine("- Delivery: `${item.delivery.name.lowercase()}`")
    item.handoffBatchId?.let { appendLine("- Handoff Batch: `$it`") }
    appendLine("- Status: `${item.status.name.lowercase()}`")
    appendLine("- Screen: `${screenLabel(session, item.screenId)}`")
    appendLine("- Target: `${item.target.describe()}`")
    item.selectedNode?.let { node ->
        appendLine("- Selected Node: `${node.uid}`")
        if (node.text.isNotEmpty()) appendLine("- Selected Text: `${node.text.joinToString(" | ")}`")
        if (node.contentDescription.isNotEmpty()) appendLine("- Selected Content Description: `${node.contentDescription.joinToString(" | ")}`")
    }
    item.sourceCandidates.firstOrNull()?.let { candidate ->
        appendLine("- Source candidate: `${candidate.file}${candidate.line?.let { line -> ":$line" }.orEmpty()}`")
    }
    appendLine()
    appendLine(item.comment.ifBlank { "(No comment)" })
    appendLine()
}

private fun screenLabel(session: FeedbackSession, screenId: String): String {
    val index = session.screens.indexOfFirst { it.screenId == screenId }
    val screen = session.screens.getOrNull(index)
    return if (screen == null) {
        screenId
    } else {
        "Screen ${index + 1} - ${screen.displayName}"
    }
}
```

- [x] **Step 4: Update list feedback summary**

In `PointPatchTools.kt`, update `pointpatch_list_feedback` output for each
session summary to include:

```kotlin
put("draftItemsCount", session.items.count { it.delivery == FeedbackDelivery.DRAFT })
put("sentBatchesCount", session.handoffBatches.size)
put("unresolvedSentItemsCount", session.items.count { it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses })
```

Define:

```kotlin
private val resolvedStatuses = setOf(FeedbackItemStatus.RESOLVED, FeedbackItemStatus.WONT_FIX)
```

- [x] **Step 5: Run MCP tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: expose feedback handoff history"
```

## Task 7: Console Layout And Readable Labels

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing asset smoke test**

Add to `FeedbackConsoleServerTest`:

```kotlin
@Test
fun consoleHtmlIncludesSelectionHandoffWorkspace() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("id=\"devicePicker\""))
    assertTrue(html.contains("id=\"modeSelect\""))
    assertTrue(html.contains("id=\"modeNavigate\""))
    assertTrue(html.contains("id=\"selectionOverlay\""))
    assertTrue(html.contains("id=\"selectionSummary\""))
    assertTrue(html.contains("id=\"draftItems\""))
    assertTrue(html.contains("id=\"sentHistory\""))
    assertTrue(html.contains("id=\"sendDraftButton\""))
    assertTrue(html.contains("id=\"clearSelectionButton\""))
    assertTrue(html.contains("id=\"clearDraftButton\""))
    assertTrue(html.contains("formatSessionLabel"))
    assertTrue(html.contains("formatScreenLabel"))
    assertTrue(html.contains("formatItemLabel"))
}
```

- [x] **Step 2: Run asset test to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace
```

Expected: FAIL because the console HTML has the old layout.

- [x] **Step 3: Replace top-level console layout**

In `FeedbackConsoleAssets.indexHtml`, change the body structure to:

```html
<header>
  <div>
    <h1>PointPatch Feedback Console</h1>
    <div id="sessionMeta" class="meta">Loading session...</div>
  </div>
  <div class="device-strip">
    <select id="devicePicker"></select>
    <button id="refreshDevicesButton">Refresh Devices</button>
    <button id="disconnectDeviceButton">Disconnect</button>
    <span id="deviceStatus" class="status-pill">No device selected</span>
  </div>
  <div class="toolbar">
    <button id="refreshButton">Refresh</button>
    <button id="captureButton" class="primary">Capture</button>
    <button id="copyMarkdownButton">Copy Agent Context</button>
  </div>
</header>
<main>
  <section class="sidebar">
    <div class="toolbar">
      <button id="newSessionButton">New Session</button>
      <button id="closeSessionButton">Close</button>
    </div>
    <h2>Sessions</h2>
    <div id="sessions" class="list"></div>
    <h2 class="section-heading">Screens</h2>
    <div id="screens" class="list"></div>
    <h2 class="section-heading">Sent History</h2>
    <div id="sentHistory" class="list"></div>
  </section>
  <section class="snapshot-pane">
    <div class="snapshot-header">
      <h2>Snapshot</h2>
      <div class="segmented" role="group" aria-label="Snapshot mode">
        <button id="modeSelect" class="active" type="button">Select</button>
        <button id="modeNavigate" type="button">Navigate</button>
      </div>
    </div>
    <div id="navigationControls" class="toolbar">
      <button id="backButton">Back</button>
      <button id="swipeUpButton">Swipe Up</button>
      <button id="swipeDownButton">Swipe Down</button>
      <button id="swipeLeftButton">Swipe Left</button>
      <button id="swipeRightButton">Swipe Right</button>
      <label><input id="captureAfterNavigation" type="checkbox" checked> Capture after navigation</label>
    </div>
    <div id="snapshot" class="snapshot">Capture a screen to begin.</div>
  </section>
  <section class="queue-pane">
    <h2>Current Selection</h2>
    <div id="selectionSummary" class="selection-summary">No selection.</div>
    <div class="toolbar">
      <button id="clearSelectionButton">Clear Selection</button>
      <button id="clearCommentButton">Clear Comment</button>
    </div>
    <h2 class="section-heading">Comment</h2>
    <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
    <div class="toolbar">
      <button id="addItemButton" class="primary" disabled>Add Item</button>
      <button id="sendDraftButton">Send Draft to Agent</button>
      <button id="clearDraftButton">Clear Draft</button>
    </div>
    <h2 class="section-heading">Draft</h2>
    <div id="draftItems" class="list"></div>
    <p id="error" class="error"></p>
  </section>
</main>
```

- [x] **Step 4: Add readable label functions**

In the console script, add:

```javascript
function shortId(value) {
  return text(value).slice(0, 8) + (text(value).length > 8 ? '...' : '');
}

function formatTime(epochMillis) {
  if (!epochMillis) return '-';
  return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatSessionLabel(session, index) {
  return `Session ${index + 1} - ${session.status}`;
}

function formatScreenLabel(screen, index) {
  return `Screen ${index + 1} - ${screen.displayName || screen.activityName || 'Screen'}`;
}

function formatItemLabel(item) {
  const number = item.sequenceNumber ? `#${item.sequenceNumber}` : '#-';
  const title = firstLine(item.comment || '(No comment)');
  return `${number} ${title}`;
}

function firstLine(value) {
  return text(value).split(/\r?\n/)[0] || '(No comment)';
}
```

Use these functions in session, screen, draft item, and sent history rendering.
UUIDs must appear only in a secondary `<span>` line.

- [x] **Step 5: Run asset tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSelectionHandoffWorkspace
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: redesign feedback console workspace"
```

## Task 8: Snapshot Selection Overlay And Modes

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing overlay asset test**

Add to `FeedbackConsoleServerTest`:

```kotlin
@Test
fun consoleHtmlImplementsSnapshotSelectionModes() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("const Mode ="))
    assertTrue(html.contains("let currentSelection"))
    assertTrue(html.contains("finishAreaSelection"))
    assertTrue(html.contains("selectNodeAtPoint"))
    assertTrue(html.contains("renderSelectionOverlay"))
    assertTrue(html.contains("naturalPointFromEvent"))
    assertTrue(html.contains("targetType"))
    assertTrue(html.contains("nodeUid"))
}
```

- [x] **Step 2: Run overlay asset test to verify failure**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlImplementsSnapshotSelectionModes
```

Expected: FAIL until overlay and mode JavaScript is added.

- [x] **Step 3: Add selection state and coordinate helpers**

Add to the console script:

```javascript
const Mode = { SELECT: 'select', NAVIGATE: 'navigate' };
let currentMode = Mode.SELECT;
let currentScreenId = null;
let currentSelection = null;
let dragStart = null;

function latestScreen() {
  const all = state.session?.screens || [];
  if (currentScreenId) {
    return all.find(screen => screen.screenId === currentScreenId) || all[all.length - 1] || null;
  }
  return all.length ? all[all.length - 1] : null;
}

function naturalPointFromEvent(event, image) {
  const rect = image.getBoundingClientRect();
  if (!image.naturalWidth || !image.naturalHeight || !rect.width || !rect.height) {
    throw new Error('Snapshot image dimensions are not available.');
  }
  return {
    x: (event.clientX - rect.left) * image.naturalWidth / rect.width,
    y: (event.clientY - rect.top) * image.naturalHeight / rect.height
  };
}

function normalizeBounds(a, b) {
  return {
    left: Math.min(a.x, b.x),
    top: Math.min(a.y, b.y),
    right: Math.max(a.x, b.x),
    bottom: Math.max(a.y, b.y)
  };
}
```

- [x] **Step 4: Render snapshot image with overlay layer**

Change snapshot rendering to:

```javascript
snapshot.innerHTML = hasScreenshot
  ? `<div class="snapshot-frame">
       <img id="snapshotImage" alt="Latest PointPatch snapshot" src="/api/screens/${encodeURIComponent(screen.screenId)}/screenshot/full">
       <div id="selectionOverlay" class="selection-overlay"></div>
     </div>`
  : `<div>${screen ? 'No screenshot artifact for selected screen.' : 'Capture a screen to begin.'}</div>`;
attachSnapshotHandlers();
renderSelectionOverlay();
```

Add CSS:

```css
.snapshot-frame { position: relative; display: inline-block; max-width: 100%; }
.snapshot-frame img { display: block; max-width: 100%; height: auto; }
.selection-overlay { position: absolute; inset: 0; pointer-events: none; }
.selection-box {
  position: absolute;
  border: 2px solid #116a5c;
  background: rgba(17, 106, 92, .12);
  border-radius: 4px;
}
.selection-label {
  position: absolute;
  transform: translateY(-100%);
  background: #116a5c;
  color: #ffffff;
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 4px 4px 0 0;
}
```

- [x] **Step 5: Implement select-mode click and drag**

Add:

```javascript
function attachSnapshotHandlers() {
  const image = document.getElementById('snapshotImage');
  if (!image) return;
  image.addEventListener('click', event => {
    if (currentMode === Mode.NAVIGATE) {
      const point = naturalPointFromEvent(event, image);
      navigate('tap', { x: point.x, y: point.y }).catch(showError);
      return;
    }
    if (!dragStart) {
      selectNodeAtPoint(event, image);
    }
  });
  image.addEventListener('pointerdown', event => {
    if (currentMode !== Mode.SELECT) return;
    dragStart = naturalPointFromEvent(event, image);
  });
  image.addEventListener('pointerup', event => {
    if (currentMode !== Mode.SELECT || !dragStart) return;
    const end = naturalPointFromEvent(event, image);
    const bounds = normalizeBounds(dragStart, end);
    dragStart = null;
    if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
      finishAreaSelection(bounds);
    } else {
      selectNodeAtPoint(event, image);
    }
  });
}

function selectNodeAtPoint(event, image) {
  const point = naturalPointFromEvent(event, image);
  const screen = latestScreen();
  const nodes = (screen?.roots || []).flatMap(root => root.mergedNodes || []);
  const containing = nodes
    .filter(node => containsPoint(node.boundsInWindow, point))
    .sort((a, b) => area(a.boundsInWindow) - area(b.boundsInWindow));
  const node = containing[0];
  if (!node) {
    showError(new Error('No component found at that point. Drag to select a custom area.'));
    return;
  }
  currentSelection = {
    targetType: 'node',
    nodeUid: node.uid,
    bounds: node.boundsInWindow,
    label: componentLabel(node)
  };
  renderSelectionOverlay();
  updateComposerState();
}

function finishAreaSelection(bounds) {
  currentSelection = {
    targetType: 'area',
    bounds,
    label: `Custom area ${Math.round(bounds.right - bounds.left)}x${Math.round(bounds.bottom - bounds.top)}`
  };
  renderSelectionOverlay();
  updateComposerState();
}
```

Add helpers:

```javascript
function containsPoint(bounds, point) {
  return point.x >= bounds.left && point.x <= bounds.right && point.y >= bounds.top && point.y <= bounds.bottom;
}

function area(bounds) {
  return Math.max(0, bounds.right - bounds.left) * Math.max(0, bounds.bottom - bounds.top);
}

function componentLabel(node) {
  const textValue = (node.text || [])[0] || (node.contentDescription || [])[0] || node.uid;
  return `Component ${textValue}`;
}
```

- [x] **Step 6: Add item submission using selected target**

Replace `addItem` with:

```javascript
async function addItem() {
  error.textContent = '';
  const screen = latestScreen();
  if (!screen || !currentSelection) {
    throw new Error('Select a component or area before adding feedback.');
  }
  await requestJson('/api/items', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      screenId: screen.screenId,
      comment: comment.value,
      targetType: currentSelection.targetType,
      nodeUid: currentSelection.nodeUid,
      bounds: currentSelection.bounds
    })
  });
  comment.value = '';
  await refresh();
}
```

Add:

```javascript
function updateComposerState() {
  addItemButton.disabled = !currentSelection || !comment.value.trim();
  selectionSummary.textContent = currentSelection
    ? `${currentSelection.label} - ${formatBounds(currentSelection.bounds)}`
    : 'No selection.';
}

function formatBounds(bounds) {
  return `${Math.round(bounds.left)},${Math.round(bounds.top)} - ${Math.round(bounds.right)},${Math.round(bounds.bottom)}`;
}
```

- [x] **Step 7: Add mode, clear, and handoff button handlers**

Add:

```javascript
function setMode(mode) {
  currentMode = mode;
  modeSelect.classList.toggle('active', mode === Mode.SELECT);
  modeNavigate.classList.toggle('active', mode === Mode.NAVIGATE);
  navigationControls.hidden = mode !== Mode.NAVIGATE;
}

function clearSelection() {
  currentSelection = null;
  renderSelectionOverlay();
  updateComposerState();
}

async function clearDraft() {
  if (!confirm('Discard all unsent draft feedback items?')) return;
  await requestJson('/api/items/draft', { method: 'DELETE' });
  clearSelection();
  await refresh();
}

async function sendDraftToAgent() {
  await requestJson('/api/agent-handoffs', { method: 'POST' });
  comment.value = '';
  clearSelection();
  await refresh();
}
```

Wire events:

```javascript
modeSelect.addEventListener('click', () => setMode(Mode.SELECT));
modeNavigate.addEventListener('click', () => setMode(Mode.NAVIGATE));
clearSelectionButton.addEventListener('click', clearSelection);
clearCommentButton.addEventListener('click', () => { comment.value = ''; updateComposerState(); });
clearDraftButton.addEventListener('click', () => clearDraft().catch(showError));
sendDraftButton.addEventListener('click', () => sendDraftToAgent().catch(showError));
comment.addEventListener('input', updateComposerState);
setMode(Mode.SELECT);
```

- [x] **Step 8: Run console asset tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [x] **Step 9: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: add screenshot selection overlay"
```

## Task 9: Documentation Updates

**Files:**

- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/output-schema.md`
- Modify: `docs/privacy.md`
- Modify: `docs/troubleshooting.md`

- [x] **Step 1: Update README**

Add to the CLI/MCP section:

```markdown
The feedback console has separate Select and Navigate modes. Select mode creates
feedback targets from a component click or custom drag area; Navigate mode sends
the existing debug-only back, tap, and swipe actions. Draft feedback can be sent
to the agent as a persisted handoff batch and reviewed later in Sent History.
```

- [x] **Step 2: Update MCP docs**

Document the console workflow:

```markdown
Console workflow:

1. Select a connected ADB device from the browser.
2. Capture a screen.
3. Use Select mode to click a component or drag a custom area.
4. Add a comment and create draft feedback.
5. Send the draft to the agent. The console records a sent handoff batch that
   `pointpatch_read_feedback` can read.
6. Use Navigate mode only when you want screenshot clicks to tap the live app.
```

Document that `Send Draft to Agent` does not call an external AI API.

- [x] **Step 3: Update output schema**

Add sections:

```markdown
## Feedback Delivery

Fields on feedback items: `sequenceNumber`, `delivery`, `handoffBatchId`,
`sentAtEpochMillis`.

`delivery` is `draft` before handoff and `sent` after a handoff batch records
the item for agent reading.

## Feedback Handoff Batch

Fields: `batchId`, `sequenceNumber`, `createdAtEpochMillis`, `itemIds`,
`markdownSnapshot`.
```

- [x] **Step 4: Update privacy and troubleshooting docs**

Add privacy note:

```markdown
Device selection is local console state. Disconnecting a device in the console
does not run `adb disconnect` and does not detach USB or Wi-Fi ADB; it clears
PointPatch's active device selection and owned bridge resources.
```

Add troubleshooting entries:

```markdown
### Capture is disabled

Select a device in the console device picker. If the device is unauthorized or
offline, fix it in `adb devices -l` first.

### I sent feedback but want to add more

After Send Draft to Agent, the draft area is cleared and the batch appears in
Sent History. Select or capture again to create a new draft batch.
```

- [x] **Step 5: Run docs checks**

Run:

```bash
rg -n "Select mode|Navigate mode|Send Draft to Agent|Sent History|handoff|device picker|adb disconnect" README.md docs
git diff --check -- README.md docs/mcp.md docs/output-schema.md docs/privacy.md docs/troubleshooting.md
```

Expected: grep output includes README and all changed docs; diff check exits 0.

- [x] **Step 6: Commit**

```bash
git add README.md docs/mcp.md docs/output-schema.md docs/privacy.md docs/troubleshooting.md
git commit -m "docs: document feedback console handoff workflow"
```

## Task 10: Final Verification And Connected Device Smoke

**Files:**

- No source edits unless verification exposes a defect.

- [x] **Step 1: Run JVM tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-cli:test :pointpatch-mcp:test
```

Expected: PASS.

- [x] **Step 2: Run sidekick unit tests**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 3: Build distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: PASS and binaries exist:

```text
pointpatch-cli/build/install/pointpatch/bin/pointpatch
pointpatch-mcp/build/install/pointpatch-mcp/bin/pointpatch-mcp
```

- [x] **Step 4: Run connected SM-G986N smoke when available**

Run:

```bash
/Users/kws/Library/Android/sdk/platform-tools/adb devices -l
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :app:installDebug
PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Expected:

- `SM_G986N` is listed in `device` state.
- the console prints a localhost URL.
- browser device picker lists `SM_G986N`.
- selecting `SM_G986N` enables Capture.
- Capture adds a screen.
- Select mode click shows a component overlay and does not tap the app.
- Select mode drag shows a custom area overlay.
- Add Item creates draft item cards with readable labels.
- Send Draft to Agent clears draft/selection/comment and creates Batch #1 under
  Sent History.
- Browser refresh preserves Sent History.
- `pointpatch_read_feedback` includes Batch #1 and the sent items.
- Navigate mode screenshot click performs a tap.
- Disconnect clears active device selection and leaves `/Users/kws/Library/Android/sdk/platform-tools/adb devices -l` connected.

If no connected device is available, record the exact `adb devices -l` output and
skip only this connected-device smoke.

Skipped in this run because `/Users/kws/Library/Android/sdk/platform-tools/adb devices -l`
returned only:

```text
List of devices attached
```

- [x] **Step 5: Cleanup only session-owned processes**

If the final console smoke started a console process, stop only that process.
Record command, exec session id or PID, port, and cleanup result. Do not use
`killall`, `pkill`, or broad ADB cleanup.

- [x] **Step 6: Commit verification notes only if docs changed**

If verification requires a docs correction, commit:

```bash
git add docs
git commit -m "docs: record feedback console handoff verification"
```

## Plan Self-Review

- Spec coverage: this plan covers device selection/disconnect, Select/Navigate
  modes, visible overlays, component and custom-area targets, draft items,
  sent handoff history, safe clear actions, readable labels, MCP read/list
  output, docs, and connected-device verification.
- Scope check: arbitrary typing, script automation, cloud sync, external AI API
  calls, Android network services, and DTA network/mocking features are excluded.
- Type consistency: delivery state uses `FeedbackDelivery`; handoff history uses
  `FeedbackHandoffBatch`; console item creation uses `AddFeedbackItemRequest`
  and `FeedbackTargetType`; device API uses `ConsoleDeviceList` and
  `SelectDeviceRequest`.
- Verification plan: implementation tasks use targeted tests; final verification
  runs JVM tests, sidekick unit tests, distribution builds, and connected-device
  smoke when a device is available.
