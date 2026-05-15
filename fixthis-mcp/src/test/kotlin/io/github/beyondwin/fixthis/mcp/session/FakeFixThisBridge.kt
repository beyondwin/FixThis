package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File

@Suppress("LongParameterList")
internal class FakeFixThisBridge(
    private val packageName: String = "io.github.beyondwin.fixthis.sample",
    private val captureError: Throwable? = null,
    private val captureRoots: List<SnapshotRootDto> = defaultCaptureRoots(),
    private val sourceIndexAvailable: Boolean = true,
    private val sourceIndex: SourceIndex? = defaultSourceIndex(),
    private val sourceIndexReadError: String? = null,
    private val devicesOverride: List<AdbDevice>? = null,
    private val devicesError: Throwable? = null,
    private val heartbeatError: Throwable? = null,
    private val statusProvider: (() -> JsonObject)? = null,
    private val snapshotMutator: (callIndex: Int, payload: JsonObject) -> JsonObject = { _, payload -> payload },
) : FixThisBridge {
    val resolvedOverrides = mutableListOf<String?>()
    val navigationRequests = mutableListOf<FeedbackNavigationRequest>()
    val launchedPackages = mutableListOf<String>()
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
    var statusCount: Int = 0
        private set
    var selectedDeviceSerial: String? = null
        private set

    override fun resolvePackageName(packageOverride: String?): String {
        resolvedOverrides += packageOverride
        return packageOverride ?: packageName
    }

    override fun devices(): List<AdbDevice> {
        devicesError?.let { throw it }
        return devicesOverride ?: listOf(
            AdbDevice(
                serial = "adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp",
                state = "device",
                model = "SM_G986N",
                product = "y2qksx",
                deviceName = "y2q",
            ),
        )
    }

    override fun selectedDeviceSerial(): String? = selectedDeviceSerial

    override fun selectDevice(serial: String) {
        selectedDeviceSerial = serial
    }

    override fun disconnectDevice() {
        selectedDeviceSerial = null
    }

    override suspend fun status(packageName: String): JsonObject {
        statusCount += 1
        return statusProvider?.invoke() ?: buildJsonObject {
            put("activity", "MainActivity")
        }
    }

    override suspend fun heartbeat(packageName: String): JsonObject {
        heartbeatError?.let { throw it }
        return status(packageName)
    }

    override fun launchApp(packageName: String) {
        launchedPackages += packageName
    }

    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject = JsonObject(emptyMap())

    override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject = buildJsonObject {
        navigationRequests += request
        put("performed", true)
        put("activity", "MainActivity")
    }

    override suspend fun captureScreenSnapshot(
        packageName: String,
        sessionId: String?,
        screenId: String?,
        destinationDirectory: File?,
    ): JsonObject {
        captureError?.let { throw it }
        lastCaptureSessionId = sessionId
        lastCaptureScreenId = screenId
        lastCaptureDestination = destinationDirectory?.absolutePath
        captureCount += 1
        val payload = buildJsonObject {
            put("activity", "MainActivity")
            put("sourceIndexAvailable", sourceIndexAvailable)
            put(
                "inspection",
                buildJsonObject {
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
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    val fallbackPath = "/repo/.fixthis/artifacts/screen-1/full.png"
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
                },
            )
        }
        return snapshotMutator(captureCount, payload)
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
            val emailLabel = FixThisNode(
                uid = "email-label",
                composeNodeId = 42,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
                text = listOf("Email address"),
                testTag = "emailField",
            )
            val visualArea = FixThisNode(
                uid = "promo-card",
                composeNodeId = 43,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(120f, 430f, 330f, 580f),
                text = listOf("Promotional card"),
                contentDescription = listOf("Promo image"),
            )
            return listOf(
                SnapshotRootDto(
                    rootIndex = 0,
                    boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                    mergedNodes = listOf(emailLabel, visualArea),
                ),
            )
        }

        private fun defaultSourceIndex(): SourceIndex = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
                    line = 37,
                    text = listOf("Email address"),
                    testTags = listOf("emailField"),
                    activityNames = listOf("MainActivity"),
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
                    line = 54,
                    text = listOf("Promotional card"),
                    contentDescriptions = listOf("Promo image"),
                    activityNames = listOf("MainActivity"),
                ),
            ),
        )
    }
}
