package io.github.pointpatch.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.sidekick.inspect.SemanticsInspector
import io.github.pointpatch.compose.sidekick.overlay.PointPatchOverlayHostLayout
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

class BridgeServer(
    private val session: SidekickSession,
    private val environment: BridgeEnvironment,
    private val socketFactory: (String) -> LocalServerSocket = { socketName -> LocalServerSocket(socketName) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    @Volatile
    private var serverSocket: LocalServerSocket? = null

    fun start(): Boolean {
        if (serverSocket != null) return false
        val socket = runCatching { socketFactory(session.socketName) }.getOrElse { return false }
        serverSocket = socket
        scope.launch {
            acceptLoop(socket)
        }
        return true
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    internal suspend fun handleRequestForTest(payload: String): String = handleRequest(payload)

    private suspend fun acceptLoop(socket: LocalServerSocket) {
        while (scope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(client: LocalSocket) {
        val input = client.inputStream
        val output = client.outputStream
        while (scope.isActive) {
            val request = try {
                BridgeProtocol.readFrame(input) ?: return
            } catch (_: Throwable) {
                return
            }
            val response = handleRequest(request)
            BridgeProtocol.writeFrame(output, response)
        }
    }

    private suspend fun handleRequest(payload: String): String {
        val request = runCatching {
            BridgeProtocol.json.decodeFromString(BridgeRequest.serializer(), payload)
        }.getOrElse {
            return BridgeProtocol.error(id = null, code = "BAD_REQUEST", message = "Invalid bridge request JSON")
        }

        if (request.token != session.token) {
            return BridgeProtocol.error(request.id, "UNAUTHORIZED", "Missing or mismatched PointPatch bridge token")
        }

        return try {
            val result = when (request.method) {
                "status" -> BridgeProtocol.json.encodeToJsonElement(environment.status())
                "inspectCurrentScreen" -> BridgeProtocol.json.encodeToJsonElement(environment.inspectCurrentScreen())
                "startFeedbackCapture" -> BridgeProtocol.json.encodeToJsonElement(
                    environment.startFeedbackCapture(request.params.longParam("timeoutMillis") ?: DefaultFeedbackTimeoutMillis),
                )
                "verifyUiChange" -> BridgeProtocol.json.encodeToJsonElement(verifyUiChange(request.params))
                "getLastAnnotation" -> BridgeProtocol.json.encodeToJsonElement(environment.getLastAnnotation())
                "readScreenshot" -> BridgeProtocol.json.encodeToJsonElement(readScreenshot(request.params))
                else -> return BridgeProtocol.error(request.id, "UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
            }
            BridgeProtocol.success(request.id, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BridgeProtocol.error(
                id = request.id,
                code = "METHOD_FAILED",
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun verifyUiChange(params: JsonObject): BridgeUiVerificationResult {
        val expectedText = params.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: return BridgeUiVerificationResult(
                verified = false,
                message = "No expectedText parameter was provided",
            )
        val inspection = environment.inspectCurrentScreen()
        val matched = inspection.roots
            .flatMap { it.mergedNodes + it.unmergedNodes }
            .flatMap { node -> node.text + node.contentDescription }
            .firstOrNull { value -> value.contains(expectedText, ignoreCase = true) }
        return BridgeUiVerificationResult(
            verified = matched != null,
            expectedText = expectedText,
            matchedText = matched,
            message = if (matched == null) "Expected text was not found on the current screen" else null,
        )
    }

    private fun readScreenshot(params: JsonObject): BridgeScreenshotReadResult {
        val explicitPath = params.stringParam("path")
        val kind = params.stringParam("kind") ?: "full"
        val screenshot = environment.getLastAnnotation()?.screenshot
        val path = explicitPath ?: when (kind) {
            "crop" -> screenshot?.cropPath
            else -> screenshot?.fullPath
        }
        require(!path.isNullOrBlank()) { "No screenshot path is available" }
        val file = File(path)
        require(file.exists() && file.isFile) { "Screenshot does not exist: $path" }
        return BridgeScreenshotReadResult(
            path = file.absolutePath,
            kind = kind,
            mimeType = "image/png",
            base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
        )
    }

    private companion object {
        const val DefaultFeedbackTimeoutMillis = 120_000L
    }
}

interface BridgeEnvironment {
    suspend fun status(): BridgeStatus
    suspend fun inspectCurrentScreen(): BridgeScreenInspection
    suspend fun startFeedbackCapture(timeoutMillis: Long): BridgeFeedbackCaptureResult
    fun getLastAnnotation(): PointPatchAnnotation?
}

@Serializable
data class BridgeStatus(
    val activity: String? = null,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val sourceIndexAvailable: Boolean,
)

@Serializable
data class BridgeScreenInspection(
    val activity: String? = null,
    val roots: List<BridgeInspectedRoot> = emptyList(),
    val errors: List<PointPatchError> = emptyList(),
)

@Serializable
data class BridgeInspectedRoot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode>,
    val unmergedNodes: List<PointPatchNode>,
)

@Serializable
data class BridgeFeedbackCaptureResult(
    val submitted: Boolean,
    val timedOut: Boolean,
    val timeoutMillis: Long,
    val annotation: PointPatchAnnotation? = null,
    val error: BridgeError? = null,
) {
    companion object {
        fun Submitted(timeoutMillis: Long, annotation: PointPatchAnnotation): BridgeFeedbackCaptureResult =
            BridgeFeedbackCaptureResult(
                submitted = true,
                timedOut = false,
                timeoutMillis = timeoutMillis,
                annotation = annotation,
            )

        fun Timeout(timeoutMillis: Long): BridgeFeedbackCaptureResult =
            BridgeFeedbackCaptureResult(
                submitted = false,
                timedOut = true,
                timeoutMillis = timeoutMillis,
            )

        fun Failed(timeoutMillis: Long, code: String, message: String): BridgeFeedbackCaptureResult =
            BridgeFeedbackCaptureResult(
                submitted = false,
                timedOut = false,
                timeoutMillis = timeoutMillis,
                error = BridgeError(code = code, message = message),
            )
    }
}

@Serializable
data class BridgeUiVerificationResult(
    val verified: Boolean,
    val expectedText: String? = null,
    val matchedText: String? = null,
    val message: String? = null,
)

@Serializable
data class BridgeScreenshotReadResult(
    val path: String,
    val kind: String,
    val mimeType: String,
    val base64: String,
)

internal object PointPatchBridgeRuntime {
    private val lock = Any()
    private var server: BridgeServer? = null
    private var environment: AndroidBridgeEnvironment? = null

    fun start(application: Application): Boolean {
        if (!application.isDebuggable()) return false
        synchronized(lock) {
            if (server != null) return false
            val store = SessionTokenStore(application)
            val session = store.create(application.packageName)
            val bridgeEnvironment = AndroidBridgeEnvironment(
                context = application,
                sidekickVersion = session.sidekickVersion,
            )
            val bridgeServer = BridgeServer(
                session = session,
                environment = bridgeEnvironment,
            )
            if (!bridgeServer.start()) return false
            store.write(session)
            environment = bridgeEnvironment
            server = bridgeServer
            return true
        }
    }

    fun onActivityResumed(activity: Activity) {
        environment?.currentActivity = WeakReference(activity)
    }

    fun onActivityDestroyed(activity: Activity) {
        val current = environment?.currentActivity?.get()
        if (current === activity) {
            environment?.currentActivity = null
        }
    }

    fun stopForTest() {
        synchronized(lock) {
            server?.stop()
            server = null
            environment = null
        }
    }
}

private class AndroidBridgeEnvironment(
    private val context: Context,
    private val sidekickVersion: String,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val inspector: SemanticsInspector = SemanticsInspector(),
) : BridgeEnvironment {
    var currentActivity: WeakReference<Activity>? = null

    override suspend fun status(): BridgeStatus {
        val inspection = inspectCurrentScreen()
        return BridgeStatus(
            activity = inspection.activity,
            rootsCount = inspection.roots.size,
            sidekickVersion = sidekickVersion,
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = context.hasAsset("pointpatch/pointpatch-source-index.json"),
        )
    }

    override suspend fun inspectCurrentScreen(): BridgeScreenInspection =
        withContext(mainDispatcher) {
            val activity = currentActivity?.get()
                ?: return@withContext BridgeScreenInspection(
                    errors = listOf(PointPatchError("NO_ACTIVITY", "No resumed Activity is available")),
                )
            val decorView = activity.window?.decorView
                ?: return@withContext BridgeScreenInspection(
                    activity = activity::class.java.name,
                    errors = listOf(PointPatchError("NO_DECOR_VIEW", "Current Activity has no decorView")),
                )
            val result = inspector.inspect(decorView)
            BridgeScreenInspection(
                activity = activity::class.java.name,
                roots = result.roots.map { root ->
                    BridgeInspectedRoot(
                        rootIndex = root.rootIndex,
                        boundsInWindow = root.boundsInWindow,
                        mergedNodes = root.mergedNodes,
                        unmergedNodes = root.unmergedNodes,
                    )
                },
                errors = result.errors,
            )
        }

    override suspend fun startFeedbackCapture(timeoutMillis: Long): BridgeFeedbackCaptureResult =
        withContext(mainDispatcher) {
            val activity = currentActivity?.get()
                ?: return@withContext BridgeFeedbackCaptureResult.Failed(
                    timeoutMillis = timeoutMillis,
                    code = "NO_ACTIVITY",
                    message = "No resumed Activity is available",
                )
            PointPatchOverlayHostLayout.attachTo(activity)
            val controller = PointPatchOverlayHostLayout.controllerFor(activity)
                ?: return@withContext BridgeFeedbackCaptureResult.Failed(
                    timeoutMillis = timeoutMillis,
                    code = "NO_OVERLAY_CONTROLLER",
                    message = "PointPatch overlay controller is unavailable",
                )
            val result = controller.startFeedbackCapture(timeoutMillis = timeoutMillis)
            if (result.submitted && result.annotation != null) {
                BridgeFeedbackCaptureResult.Submitted(timeoutMillis, result.annotation)
            } else {
                BridgeFeedbackCaptureResult.Timeout(timeoutMillis)
            }
        }

    override fun getLastAnnotation(): PointPatchAnnotation? {
        val activity = currentActivity?.get() ?: return null
        return PointPatchOverlayHostLayout.controllerFor(activity)?.lastAnnotation
    }
}

private fun Context.hasAsset(path: String): Boolean =
    runCatching {
        assets.open(path).use { true }
    }.getOrDefault(false)

private fun Application.isDebuggable(): Boolean =
    runCatching {
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }.getOrDefault(false)

private fun JsonObject.stringParam(name: String): String? =
    get(name)?.jsonPrimitive?.content

private fun JsonObject.longParam(name: String): Long? =
    stringParam(name)?.toLongOrNull()
