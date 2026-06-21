package io.github.beyondwin.fixthis.mcp.session.runtime

internal object AndroidRuntimeEvidenceCapture {
    fun commandLabel(type: RuntimeEvidenceType): String = when (type) {
        RuntimeEvidenceType.LOGCAT_WINDOW -> "adb logcat -d"
        RuntimeEvidenceType.FRAME_SUMMARY -> "adb shell dumpsys gfxinfo <package>"
        RuntimeEvidenceType.MEMORY_SUMMARY -> "adb shell dumpsys meminfo <package>"
        RuntimeEvidenceType.TRACE_ARTIFACT -> "perfetto or simpleperf capture"
    }
}
