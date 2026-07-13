package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.cli.bridge.BridgeRequestScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val RUNTIME_EVIDENCE_BRIDGE_TIMEOUT_MILLIS = 1_250L
private const val RUNTIME_EVIDENCE_ENRICHMENT_DEADLINE_MILLIS = 1_500L

fun BridgeClient.runtimeEvidenceCapabilities(packageName: String): CliRuntimeEvidenceCapabilities {
    val scope = runtimeEvidenceScope()
    return AndroidRuntimeEvidenceCollector(scope.adb, scope.deviceSerial()).capabilities(packageName)
}

fun BridgeClient.runtimeEvidenceContext(packageName: String): CliRuntimeEvidenceContext {
    val scope = runtimeEvidenceScope()
    val adbContext = AndroidRuntimeEvidenceCollector(scope.adb, scope.deviceSerial()).context(packageName)
    val (status, snapshot) = runtimeEvidenceBridgeContext(scope, packageName)
    return adbContext.copy(
        installEpochMillis = status.stringValue("installEpochMillis")?.toLongOrNull() ?: adbContext.installEpochMillis,
        currentActivity = status.stringValue("activity") ?: snapshot.stringValue("activity"),
        bridgeProtocolVersion = status.stringValue("bridgeProtocolVersion"),
        currentScreenFingerprint = snapshot.stringValue("fingerprint"),
    )
}

fun BridgeClient.collectRuntimeEvidence(
    packageName: String,
    kind: CliRuntimeEvidenceKind,
    screenCapturedAtEpochMillis: Long,
): CliRuntimeEvidenceResult {
    val scope = runtimeEvidenceScope()
    return AndroidRuntimeEvidenceCollector(scope.adb, scope.deviceSerial()).collect(
        packageName = packageName,
        kind = kind,
        screenCapturedAtEpochMillis = screenCapturedAtEpochMillis,
    )
}

private fun BridgeClient.runtimeEvidenceScope(): BridgeRequestScope {
    val scope = currentRequestScope()
    if (scope.selectedDeviceSerial != null) return scope
    val serial = scope.adb.devices().single { it.state == "device" }.serial
    return BridgeRequestScope(selectedDeviceSerial = serial, adb = scope.adb.forDevice(serial))
}

private fun BridgeClient.runtimeEvidenceBridgeContext(
    scope: BridgeRequestScope,
    packageName: String,
): Pair<JsonObject?, JsonObject?> {
    val executor = Executors.newFixedThreadPool(2) { task ->
        thread(start = false, isDaemon = true, name = "fixthis-runtime-evidence-bridge", block = task::run)
    }
    return try {
        val status = executor.submit<JsonObject?> {
            runtimeEvidenceRequest(scope, packageName, "status")
        }
        val snapshot = executor.submit<JsonObject?> {
            runtimeEvidenceRequest(scope, packageName, "captureScreenSnapshot")
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUNTIME_EVIDENCE_ENRICHMENT_DEADLINE_MILLIS)
        status.getBefore(deadlineNanos) to snapshot.getBefore(deadlineNanos)
    } finally {
        executor.shutdownNow()
    }
}

private fun BridgeClient.runtimeEvidenceRequest(
    scope: BridgeRequestScope,
    packageName: String,
    method: String,
): JsonObject? = runCatching {
    runBlocking {
        requestInScope(
            scope = scope,
            packageName = packageName,
            method = method,
            readTimeoutMillis = RUNTIME_EVIDENCE_BRIDGE_TIMEOUT_MILLIS,
        )
    }
}.getOrNull()

private fun <T> Future<T>.getBefore(deadlineNanos: Long): T? {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0) {
        cancel(true)
        return null
    }
    return runCatching { get(remainingNanos, TimeUnit.NANOSECONDS) }
        .onFailure { cancel(true) }
        .getOrNull()
}

private fun JsonObject?.stringValue(name: String): String? = this
    ?.get(name)
    ?.jsonPrimitive
    ?.contentOrNull

private fun BridgeRequestScope.deviceSerial(): String = requireNotNull(selectedDeviceSerial)
