package io.github.beyondwin.fixthis.mcp.tools

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.collectRuntimeEvidence
import io.github.beyondwin.fixthis.cli.runtime.runtimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.runtimeEvidenceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface RuntimeEvidenceBridge {
    fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities

    suspend fun context(packageName: String): CliRuntimeEvidenceContext

    suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult

    fun capabilitiesForDevice(
        packageName: String,
        deviceSerial: String,
    ): CliRuntimeEvidenceCapabilities = capabilities(packageName)

    suspend fun contextForDevice(
        packageName: String,
        deviceSerial: String,
    ): CliRuntimeEvidenceContext = context(packageName)

    suspend fun collectForDevice(
        packageName: String,
        deviceSerial: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult = collect(packageName, kind, screenCapturedAtEpochMillis)
}

@Suppress("MaxLineLength")
internal class CliRuntimeEvidenceBridge(
    private val client: BridgeClient,
) : RuntimeEvidenceBridge {
    override fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities = client.runtimeEvidenceCapabilities(packageName)

    override fun capabilitiesForDevice(packageName: String, deviceSerial: String): CliRuntimeEvidenceCapabilities = client.runtimeEvidenceCapabilities(packageName, deviceSerial)

    override suspend fun context(packageName: String): CliRuntimeEvidenceContext = runInterruptible(Dispatchers.IO) {
        client.runtimeEvidenceContext(packageName)
    }

    override suspend fun contextForDevice(packageName: String, deviceSerial: String): CliRuntimeEvidenceContext = runInterruptible(Dispatchers.IO) { client.runtimeEvidenceContext(packageName, deviceSerial) }

    override suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult = runInterruptible(Dispatchers.IO) {
        client.collectRuntimeEvidence(packageName, kind, screenCapturedAtEpochMillis)
    }

    override suspend fun collectForDevice(
        packageName: String,
        deviceSerial: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult = runInterruptible(Dispatchers.IO) {
        client.collectRuntimeEvidence(packageName, kind, screenCapturedAtEpochMillis, deviceSerial)
    }
}

internal class UnavailableRuntimeEvidenceBridge : RuntimeEvidenceBridge {
    override fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities = CliRuntimeEvidenceCapabilities(
        baselineAvailable = false,
        supportedCollectors = emptySet(),
    )

    override suspend fun context(packageName: String): CliRuntimeEvidenceContext = error(
        "Runtime evidence collection is unavailable for this bridge",
    )

    override suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult = error("Runtime evidence collection is unavailable for this bridge")
}
