package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbExecutionResult
import io.github.beyondwin.fixthis.cli.AdbFacade
import java.time.ZoneId

class AndroidRuntimeEvidenceCollector(
    private val adb: AdbFacade,
    private val deviceSerial: String,
    private val limits: CliRuntimeEvidenceLimits = CliRuntimeEvidenceLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities {
        val context = collectContext(packageName).context
        val supported = when {
            !context.packageAvailable -> emptySet()
            context.pid == null -> setOf(CliRuntimeEvidenceKind.CONTEXT, CliRuntimeEvidenceKind.LOGCAT_WINDOW)
            else -> CliRuntimeEvidenceKind.entries.toSet()
        }
        return CliRuntimeEvidenceCapabilities(
            baselineAvailable = context.packageAvailable,
            supportedCollectors = supported,
            limits = limits,
        )
    }

    fun context(packageName: String): CliRuntimeEvidenceContext = collectContext(packageName).context

    fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult {
        val startedAt = clock()
        val baseline = collectContext(packageName)
        val plan = RuntimeEvidenceCommandPlanner.baseline(
            packageName = packageName,
            pid = baseline.context.pid,
            logcatSince = RuntimeEvidenceResultSupport.logcatSince(screenCapturedAtEpochMillis),
        )
        if (kind != CliRuntimeEvidenceKind.CONTEXT && !baseline.context.packageAvailable) {
            return unavailableResult(startedAt, kind, RuntimeEvidenceFailure.PACKAGE_NOT_AVAILABLE)
        }
        return when (kind) {
            CliRuntimeEvidenceKind.CONTEXT -> contextResult(startedAt, baseline)
            CliRuntimeEvidenceKind.LOGCAT_WINDOW -> logcatResult(startedAt, baseline.context.pid, plan)
            CliRuntimeEvidenceKind.MEMORY_SUMMARY -> if (baseline.context.pid == null) {
                unavailableResult(startedAt, kind, RuntimeEvidenceFailure.PROCESS_NOT_RUNNING)
            } else {
                singleResult(startedAt, kind, plan.memory)
            }
            CliRuntimeEvidenceKind.FRAME_SUMMARY -> if (baseline.context.pid == null) {
                unavailableResult(startedAt, kind, RuntimeEvidenceFailure.PROCESS_NOT_RUNNING)
            } else {
                singleResult(startedAt, kind, plan.frame)
            }
        }
    }

    private fun collectContext(packageName: String): CollectedContext {
        val plan = RuntimeEvidenceCommandPlanner.context(packageName)
        val packagePath = execute(plan.packagePath)
        val pid = execute(plan.pid)
        val packageInfo = execute(plan.packageInfo)
        val context = CliRuntimeEvidenceContext(
            deviceSerial = deviceSerial,
            packageName = packageName,
            packageAvailable = packagePath.exitCode == 0 && packagePath.stdout.lineSequence().any { it.trim().startsWith("package:") },
            pid = if (pid.exitCode == 0) RuntimeEvidenceParsers.pid(pid.stdout) else null,
            installEpochMillis = if (packageInfo.exitCode == 0) {
                RuntimeEvidenceParsers.lastUpdateEpochMillis(packageInfo.stdout, zoneId)
            } else {
                null
            },
            currentActivity = null,
            bridgeProtocolVersion = null,
            currentScreenFingerprint = null,
        )
        return CollectedContext(context, plan, listOf(packagePath, pid, packageInfo))
    }

    private fun contextResult(startedAt: Long, baseline: CollectedContext): CliRuntimeEvidenceResult {
        val warnings = baseline.results.flatMapTo(linkedSetOf()) { RuntimeEvidenceParsers.warnings(it) }
        val failures = baseline.results.map(RuntimeEvidenceParsers::failure)
        val output = RuntimeEvidenceResultSupport.mergeBounded(
            listOf(
                "package" to baseline.results[0],
                "pid" to baseline.results[1],
                "package_info" to baseline.results[2],
            ),
            limits.baselineBytes,
        )
        return CliRuntimeEvidenceResult(
            kind = CliRuntimeEvidenceKind.CONTEXT,
            status = RuntimeEvidenceResultSupport.aggregateStatus(failures, warnings),
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = clock(),
            command = baseline.plan.packagePath.arguments,
            output = output,
            warnings = warnings,
            failureCode = failures.firstOrNull { it != RuntimeEvidenceFailure.NONE }?.code,
        )
    }

    private fun logcatResult(
        startedAt: Long,
        pid: Int?,
        plan: RuntimeEvidenceCommandPlan,
    ): CliRuntimeEvidenceResult {
        val warnings = linkedSetOf<String>()
        val timestamp = plan.appLog.arguments.valueAfter("-T")
        val app = if (pid == null) {
            AppLogCollection(
                command = emptyList(),
                result = emptyResult(),
                failure = RuntimeEvidenceFailure.PROCESS_NOT_RUNNING,
            )
        } else {
            collectAppLog(pid, timestamp, warnings)
        }
        val crashResult = execute(plan.crashLog)
        val exitResult = execute(plan.exitInfo)
        val results = listOf(app.result, crashResult, exitResult)
        results.flatMapTo(warnings) { RuntimeEvidenceParsers.warnings(it) }
        val failures = listOf(app.failure, RuntimeEvidenceParsers.failure(crashResult), RuntimeEvidenceParsers.failure(exitResult))
        return CliRuntimeEvidenceResult(
            kind = CliRuntimeEvidenceKind.LOGCAT_WINDOW,
            status = RuntimeEvidenceResultSupport.aggregateStatus(failures, warnings),
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = clock(),
            command = app.command,
            output = RuntimeEvidenceResultSupport.mergeBounded(
                listOf("app_logcat" to app.result, "crash_logcat" to crashResult, "exit_info" to exitResult),
                limits.logcatBytes,
            ),
            warnings = warnings,
            failureCode = failures.firstOrNull { it != RuntimeEvidenceFailure.NONE }?.code,
        )
    }

    private fun collectAppLog(
        pid: Int,
        timestamp: String?,
        warnings: MutableSet<String>,
    ): AppLogCollection {
        var usePid = true
        var useTimestamp = timestamp != null
        repeat(MAX_LOGCAT_ATTEMPTS) {
            val command = RuntimeEvidenceCommandPlanner.appLog(
                pid = pid.takeIf { usePid },
                logcatSince = timestamp.takeIf { useTimestamp },
            )
            val result = execute(command)
            val failure = RuntimeEvidenceParsers.failure(result)
            if (failure != RuntimeEvidenceFailure.UNSUPPORTED) {
                return AppLogCollection(command.arguments, result, failure)
            }
            val changed = when (RuntimeEvidenceParsers.unsupportedLogcatOption(result)) {
                UnsupportedLogcatOption.PID -> usePid.disable(warnings, "pid_filter_unsupported") { usePid = false }
                UnsupportedLogcatOption.TIMESTAMP -> useTimestamp.disable(warnings, "timestamp_filter_unsupported") { useTimestamp = false }
                UnsupportedLogcatOption.BOTH -> {
                    val pidChanged = usePid.disable(warnings, "pid_filter_unsupported") { usePid = false }
                    val timestampChanged = useTimestamp.disable(warnings, "timestamp_filter_unsupported") { useTimestamp = false }
                    pidChanged || timestampChanged
                }
                UnsupportedLogcatOption.UNKNOWN -> false
            }
            if (!changed) return AppLogCollection(command.arguments, result, failure)
        }
        error("Logcat fallback attempts exceeded their fixed option state space")
    }

    private fun singleResult(
        startedAt: Long,
        kind: CliRuntimeEvidenceKind,
        command: RuntimeEvidenceCommand,
    ): CliRuntimeEvidenceResult {
        val result = execute(command)
        val failure = RuntimeEvidenceParsers.failure(result)
        val warnings = RuntimeEvidenceParsers.warnings(result)
        val output = RuntimeEvidenceResultSupport.output(result)
        return CliRuntimeEvidenceResult(
            kind = kind,
            status = RuntimeEvidenceResultSupport.status(failure, warnings, output.isNotBlank()),
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = clock(),
            command = command.arguments,
            output = RuntimeEvidenceResultSupport.boundedUtf8(output, limits.summaryBytes),
            warnings = warnings,
            failureCode = failure.code,
        )
    }

    private fun unavailableResult(
        startedAt: Long,
        kind: CliRuntimeEvidenceKind,
        failure: RuntimeEvidenceFailure,
    ): CliRuntimeEvidenceResult = CliRuntimeEvidenceResult(
        kind = kind,
        status = CliRuntimeEvidenceStatus.FAILED,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = clock(),
        command = emptyList(),
        output = "",
        failureCode = failure.code,
    )

    private fun execute(command: RuntimeEvidenceCommand): AdbExecutionResult = adb.execute(command.arguments, command.limits)

    private data class CollectedContext(
        val context: CliRuntimeEvidenceContext,
        val plan: RuntimeEvidenceContextPlan,
        val results: List<AdbExecutionResult>,
    )

    private data class AppLogCollection(
        val command: List<String>,
        val result: AdbExecutionResult,
        val failure: RuntimeEvidenceFailure,
    )

    private companion object {
        const val MAX_LOGCAT_ATTEMPTS = 3

        fun emptyResult() = AdbExecutionResult(0, "", "", false, false, false)

        fun List<String>.valueAfter(option: String): String? = indexOf(option)
            .takeIf { it >= 0 }
            ?.let { getOrNull(it + 1) }

        inline fun Boolean.disable(
            warnings: MutableSet<String>,
            warning: String,
            disable: () -> Unit,
        ): Boolean {
            if (!this) return false
            disable()
            warnings += warning
            return true
        }
    }
}
