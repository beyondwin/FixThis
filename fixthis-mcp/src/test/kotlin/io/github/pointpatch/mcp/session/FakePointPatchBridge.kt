package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.AdbDevice
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.compose.core.source.SourceIndex
import io.github.pointpatch.compose.core.source.SourceIndexEntry
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal class FakePointPatchBridge(
    private val packageName: String = "io.github.pointpatch.sample",
    private val captureError: Throwable? = null,
    private val captureRoots: List<SnapshotRootDto> = defaultCaptureRoots(),
    private val sourceIndexAvailable: Boolean = true,
    private val sourceIndex: SourceIndex? = defaultSourceIndex(),
    private val sourceIndexReadError: String? = null,
) : PointPatchBridge {
    val resolvedOverrides = mutableListOf<String?>()
    val navigationRequests = mutableListOf<FeedbackNavigationRequest>()
    var lastCaptureSessionId: String? = null
        private set
    var lastCaptureScreenId: String? = null
        private set
    var lastCaptureDestination: String? = null
        private set
    var captureCount: Int = 0
        private set
    var readSourceIndexCount: Int = 0
        private set
    var selectedDeviceSerial: String? = null
        private set

    override fun resolvePackageName(packageOverride: String?): String {
        resolvedOverrides += packageOverride
        return packageOverride ?: packageName
    }

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

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
        JsonObject(emptyMap())

    override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
        buildJsonObject {
            navigationRequests += request
            put("performed", true)
            put("activity", "MainActivity")
        }

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject = buildJsonObject {
        captureError?.let { throw it }
        lastCaptureSessionId = sessionId
        lastCaptureScreenId = screenId
        lastCaptureDestination = destinationDirectory?.absolutePath
        captureCount += 1
        put("activity", "MainActivity")
        put("sourceIndexAvailable", sourceIndexAvailable)
        put("inspection", buildJsonObject {
            put("activity", "MainActivity")
            put(
                "roots",
                JsonArray(
                    captureRoots.map { root ->
                        McpProtocol.json.encodeToJsonElement(SnapshotRootDto.serializer(), root)
                    },
                ),
            )
            put("sourceIndexAvailable", sourceIndexAvailable)
            put("errors", JsonArray(emptyList()))
        })
        put("screenshot", buildJsonObject {
            val fallbackPath = "/repo/.pointpatch/artifacts/screen-1/full.png"
            val capturedPath = destinationDirectory
                ?.resolve("${screenId ?: "screen-1"}-full.png")
                ?.absolutePath
                ?.also { path ->
                    runCatching {
                        File(path).also { file ->
                            if (file.parentFile?.exists() != true) file.parentFile?.mkdirs()
                            if (file.parentFile?.exists() == true) {
                                file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
                            }
                        }
                    }
                }
            put("desktopFullPath", capturedPath ?: fallbackPath)
            put("width", 720)
            put("height", 1600)
        })
    }

    override suspend fun readSourceIndex(packageName: String): JsonObject = buildJsonObject {
        readSourceIndexCount += 1
        put("sourceIndexAvailable", sourceIndexAvailable && sourceIndex != null && sourceIndexReadError == null)
        sourceIndex?.takeIf { sourceIndexAvailable && sourceIndexReadError == null }?.let {
            put("sourceIndex", McpProtocol.json.encodeToJsonElement(SourceIndex.serializer(), it))
        }
        sourceIndexReadError?.let { put("sourceIndexError", it) }
    }

    companion object {
        private fun defaultCaptureRoots(): List<SnapshotRootDto> {
            val emailLabel = PointPatchNode(
                uid = "email-label",
                composeNodeId = 42,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = PointPatchRect(28f, 77f, 692f, 186f),
                text = listOf("Email address"),
                testTag = "emailField",
            )
            val visualArea = PointPatchNode(
                uid = "promo-card",
                composeNodeId = 43,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = PointPatchRect(120f, 430f, 330f, 580f),
                text = listOf("Promotional card"),
                contentDescription = listOf("Promo image"),
            )
            return listOf(
                SnapshotRootDto(
                    rootIndex = 0,
                    boundsInWindow = PointPatchRect(0f, 0f, 720f, 1600f),
                    mergedNodes = listOf(emailLabel, visualArea),
                ),
            )
        }

        private fun defaultSourceIndex(): SourceIndex =
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                        line = 37,
                        text = listOf("Email address"),
                        testTags = listOf("emailField"),
                        activityNames = listOf("MainActivity"),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                        line = 54,
                        text = listOf("Promotional card"),
                        contentDescriptions = listOf("Promo image"),
                        activityNames = listOf("MainActivity"),
                    ),
                ),
            )
    }
}
