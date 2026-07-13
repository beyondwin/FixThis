package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbExecutionLimits

data class RuntimeEvidenceCommand(
    val arguments: List<String>,
    val limits: AdbExecutionLimits,
)

data class RuntimeEvidenceContextPlan(
    val packagePath: RuntimeEvidenceCommand,
    val pid: RuntimeEvidenceCommand,
    val packageInfo: RuntimeEvidenceCommand,
) {
    fun allCommands(): List<RuntimeEvidenceCommand> = listOf(packagePath, pid, packageInfo)
}

data class RuntimeEvidenceCommandPlan(
    val appLog: RuntimeEvidenceCommand,
    val crashLog: RuntimeEvidenceCommand,
    val exitInfo: RuntimeEvidenceCommand,
    val memory: RuntimeEvidenceCommand,
    val frame: RuntimeEvidenceCommand,
) {
    fun allCommands(): List<RuntimeEvidenceCommand> = listOf(appLog, crashLog, exitInfo, memory, frame)
}

object RuntimeEvidenceCommandPlanner {
    private val applicationId = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val contextLimits = AdbExecutionLimits(750, 128 * 1024, 128 * 1024)
    private val logcatLimits = AdbExecutionLimits(1_500, 512 * 1024, 512 * 1024)
    private val summaryLimits = AdbExecutionLimits(1_250, 128 * 1024, 128 * 1024)

    fun context(packageName: String): RuntimeEvidenceContextPlan {
        validatePackageName(packageName)
        return RuntimeEvidenceContextPlan(
            packagePath = RuntimeEvidenceCommand(listOf("shell", "pm", "path", packageName), contextLimits),
            pid = RuntimeEvidenceCommand(listOf("shell", "pidof", packageName), contextLimits),
            packageInfo = RuntimeEvidenceCommand(listOf("shell", "dumpsys", "package", packageName), contextLimits),
        )
    }

    fun baseline(
        packageName: String,
        pid: Int?,
        logcatSince: String?,
    ): RuntimeEvidenceCommandPlan {
        validatePackageName(packageName)
        val appLog = appLog(pid, logcatSince)
        return RuntimeEvidenceCommandPlan(
            appLog = appLog,
            crashLog = RuntimeEvidenceCommand(listOf("logcat", "-b", "crash", "-d", "-v", "epoch", "-t", "200"), logcatLimits),
            exitInfo = RuntimeEvidenceCommand(listOf("shell", "dumpsys", "activity", "exit-info", packageName), summaryLimits),
            memory = RuntimeEvidenceCommand(listOf("shell", "dumpsys", "meminfo", packageName), summaryLimits),
            frame = RuntimeEvidenceCommand(listOf("shell", "dumpsys", "gfxinfo", packageName), summaryLimits),
        )
    }

    fun appLog(pid: Int?, logcatSince: String?): RuntimeEvidenceCommand {
        val appLogArguments = buildList {
            addAll(listOf("logcat", "-d", "-v", "epoch"))
            if (pid != null && pid > 0) addAll(listOf("--pid", pid.toString()))
            if (!logcatSince.isNullOrBlank()) addAll(listOf("-T", logcatSince))
            addAll(listOf("-t", "2000"))
        }
        return RuntimeEvidenceCommand(appLogArguments, logcatLimits)
    }

    fun appLogTail(pid: Int?): RuntimeEvidenceCommand = appLog(pid, logcatSince = null)

    fun validatePackageName(packageName: String) {
        require(applicationId.matches(packageName)) { "Invalid Android application id: $packageName" }
    }
}
