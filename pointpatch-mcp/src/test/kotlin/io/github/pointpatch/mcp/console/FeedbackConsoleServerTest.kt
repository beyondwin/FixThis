package io.github.pointpatch.mcp.console

import io.github.pointpatch.cli.AdbDevice
import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.mcp.session.CapturedScreen
import io.github.pointpatch.mcp.session.FakePointPatchBridge
import io.github.pointpatch.mcp.session.FeedbackItem
import io.github.pointpatch.mcp.session.FeedbackNavigationAction
import io.github.pointpatch.mcp.session.FeedbackScreenRoot
import io.github.pointpatch.mcp.session.FeedbackSessionPaths
import io.github.pointpatch.mcp.session.FeedbackSessionPersistence
import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.session.FeedbackScreenshot
import io.github.pointpatch.mcp.session.FeedbackTarget
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackConsoleServerTest {
    @Test
    fun servesIndexAndSessionJson() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = URL(server.url).readText()
            assertTrue(index.contains("PointPatch Feedback Console"))

            val session = URL("${server.url}/api/session").readText()
            assertTrue(session.contains("io.github.pointpatch.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun consoleHtmlIncludesSessionPickerControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"sessions\""))
        assertTrue(html.contains("id=\"newSessionButton\""))
        assertTrue(html.contains("id=\"closeSessionButton\""))
        assertTrue(html.contains("/api/sessions"))
        assertTrue(html.contains("/api/session/open"))
    }

    @Test
    fun consoleHtmlIncludesNavigationControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"backButton\""))
        assertTrue(html.contains("id=\"captureAfterNavigation\""))
        assertTrue(html.contains("/api/navigation"))
    }

    @Test
    fun consoleHtmlIncludesSelectionHandoffWorkspace() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"devicePicker\""))
        assertTrue(html.contains("id=\"refreshDevicesButton\""))
        assertTrue(html.contains("id=\"disconnectDeviceButton\""))
        assertTrue(html.contains("id=\"deviceStatus\""))
        assertTrue(html.contains("id=\"modeSelect\""))
        assertTrue(html.contains("id=\"modeNavigate\""))
        assertTrue(html.contains("id=\"selectionOverlay\""))
        assertTrue(html.contains("id=\"selectionSummary\""))
        assertTrue(html.contains("id=\"draftItems\""))
        assertTrue(html.contains("id=\"sentHistory\""))
        assertTrue(html.contains("id=\"sendDraftButton\""))
        assertTrue(html.contains("id=\"clearSelectionButton\""))
        assertTrue(html.contains("id=\"clearCommentButton\""))
        assertTrue(html.contains("id=\"clearDraftButton\""))
        assertTrue(html.contains("/api/devices"))
        assertTrue(html.contains("/api/device/select"))
        assertTrue(html.contains("/api/device/disconnect"))
        assertTrue(html.contains("/api/items/draft"))
        assertTrue(html.contains("/api/agent-handoffs"))
        assertTrue(html.contains("function refreshDevices"))
        assertTrue(html.contains("function selectDevice"))
        assertTrue(html.contains("function disconnectDevice"))
        assertTrue(html.contains("device.model || 'Unknown model'"))
        assertTrue(html.contains("device.product || device.deviceName || 'device'"))
        assertTrue(html.contains("labelParts.push(device.serial)"))
        assertTrue(html.contains("function switchSnapshotMode"))
        assertTrue(html.contains("function clearVisibleSelection"))
        assertTrue(html.contains("function clearDraft"))
        assertTrue(html.contains("function sendDraft"))
        assertTrue(html.contains("let snapshotMode = 'select'"))
        assertTrue(html.contains("Switch to Navigate mode to tap the device."))
        assertTrue(html.contains("Select device..."))
        assertTrue(html.contains("option.disabled = device.state !== 'device'"))
        assertTrue(html.contains("selectionSummary.textContent = 'No selection.'"))
        assertTrue(html.contains("addItemButton.disabled = true"))
        assertTrue(html.contains("formatSessionLabel"))
        assertTrue(html.contains("formatSessionSummary"))
        assertTrue(html.contains("session.screensCount"))
        assertTrue(html.contains("session.draftItemsCount"))
        assertTrue(html.contains("session.sentBatchesCount"))
        assertTrue(html.contains("draft item"))
        assertTrue(html.contains("sent batch"))
        assertTrue(html.contains("formatScreenLabel"))
        assertTrue(html.contains("formatItemLabel"))
        assertTrue(html.contains("function findScreen"))
        assertTrue(html.contains("function screenLabelForId"))
        assertTrue(html.contains("function targetLabel"))
        assertTrue(html.contains("function deliveryLabel"))
        assertTrue(html.contains("function formatBatchLabel"))
        assertTrue(html.contains("function formatBatchDetails"))
        assertTrue(html.contains("function batchItems"))
        assertTrue(html.contains("function formatBatchItemSummary"))
        assertTrue(html.contains("session.handoffBatches"))
        assertTrue(html.contains("Batch #"))
        assertTrue(html.contains("Not sent"))
        assertTrue(html.contains("Unbatched sent item"))
        assertTrue(html.contains("Missing batch metadata"))
    }

    @Test
    fun consoleHtmlReportsNavigationCaptureErrors() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("navigation.captureError"))
        assertTrue(html.contains("Navigation performed, but capture failed:"))
    }

    @Test
    fun consoleHtmlLegacyAddItemUsesLatestScreenSelectionPayload() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("screenId: screen.screenId"))
        assertTrue(html.contains("targetType: 'area'"))
        assertTrue(html.contains("Capture a screen before adding feedback."))
        assertTrue(html.contains("comment.value.trim()"))
        assertTrue(html.contains("Enter a comment before adding feedback."))
    }

    @Test
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }

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
            val response = pointPatchJson.parseToJsonElement(body).jsonObject
            assertTrue(response["handoffBatches"]?.jsonArray.orEmpty().isNotEmpty())
            assertEquals("sent", response["items"]?.jsonArray?.single()?.jsonObject?.get("delivery")?.jsonPrimitive?.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiReturnsConflictWhenNoDraftItemsExist() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val handoff = URL("${server.url}/api/agent-handoffs").openConnection() as HttpURLConnection
            handoff.requestMethod = "POST"
            handoff.doOutput = true
            handoff.setRequestProperty("Content-Type", "application/json")
            handoff.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(409, handoff.responseCode)
            assertTrue(handoff.errorStream.bufferedReader().readText().contains("NO_DRAFT_FEEDBACK"))
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
            val comments = pointPatchJson.parseToJsonElement(body).jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject.getValue("comment").jsonPrimitive.content }
            assertEquals(listOf("Sent"), comments)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsBadRequestForBlankSerial() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":" "}""".toByteArray()) }

            assertEquals(400, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("Device serial"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForMissingSerialWithoutChangingSelection() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"missing-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForOfflineDeviceWithoutChangingSelection() {
        val bridge = DeviceListBridge(
            listOf(
                AdbDevice(
                    serial = "offline-device",
                    state = "offline",
                    model = "Pixel_8",
                    product = "shiba",
                    deviceName = "shiba",
                ),
            ),
        )
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"offline-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
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
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"node","nodeUid":"${node.uid}","bounds":{"left":200.0,"top":300.0,"right":260.0,"bottom":340.0},"comment":"Button copy is unclear"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(200, connection.responseCode)
            val item = pointPatchJson.decodeFromString(FeedbackItem.serializer(), connection.inputStream.bufferedReader().readText())
            assertEquals(FeedbackTarget.Node(node.uid, node.boundsInWindow), item.target)
            assertEquals(node, item.selectedNode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnknownScreenId() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"missing-screen","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad screen"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("SCREEN_NOT_FOUND"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnsupportedFields() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad field","screenID":"typo"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported feedback item field"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForInvalidAreaBounds() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":-1.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad bounds"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiListsWorkspaces() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions").readText()

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val matching = service.openSession("io.github.pointpatch.sample", newSession = true)
        val other = service.openSession("io.github.pointpatch.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions?packageName=io.github.pointpatch.sample").readText()

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"${first.sessionId}"}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains(first.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionApiReturnsServerErrorForSessionSaveFailure() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-save-fail").toFile()
        try {
            projectRoot.resolve(".pointpatch").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("SESSION_SAVE_FAILED:"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun closeSessionApiClosesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertTrue(response.contains(session.sessionId))
            assertTrue(response.contains("closed"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun closeSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiPerformsAction() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, pointPatchJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val payloads = listOf(
                """{"action":"back","sequence":[]}""",
                """{"action":"back","script":"adb shell input keyevent BACK"}""",
            )

            payloads.forEach { payload ->
                val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray()) }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported navigation field"))
            }
            assertTrue(bridge.navigationRequests.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun startUrlUsesConfiguredHostAndBoundPort() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, host = "127.0.0.1", port = 0)
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
            assertTrue(URL(url).readText().contains("PointPatch Feedback Console"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesSessionOwnedScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/screens/screen-1/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun servesLegacyScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val artifact = projectRoot.resolve(".pointpatch/artifacts/screen-1/full.png")
            artifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            artifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = LegacyScreenshotBridge(artifact),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/screens/screen-1/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private fun FeedbackSessionService.captureFakeScreenForTest(sessionId: String): CapturedScreen =
        runBlocking { captureScreen(sessionId) }

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: CapturedScreen): CapturedScreen =
        javaClass.getDeclaredField("store").let { field ->
            field.isAccessible = true
            (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
        }

    private class DeviceListBridge(private val devices: List<AdbDevice>) : PointPatchBridge {
        var selectedDeviceSerial: String? = null
            private set

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override fun devices(): List<AdbDevice> = devices

        override fun selectedDeviceSerial(): String? = selectedDeviceSerial

        override fun selectDevice(serial: String) {
            selectedDeviceSerial = serial
        }

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private class SessionScreenshotBridge(private val pngBytes: ByteArray) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(pngBytes)
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }

    private class LegacyScreenshotBridge(private val artifact: File) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }
}
