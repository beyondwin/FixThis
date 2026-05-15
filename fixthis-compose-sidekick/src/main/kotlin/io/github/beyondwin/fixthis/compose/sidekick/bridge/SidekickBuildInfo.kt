package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.content.Context
import io.github.beyondwin.fixthis.compose.sidekick.R

internal data class SidekickBuildInfo(
    val buildEpochMs: Long,
    val gitSha: String,
)

internal interface SidekickBuildInfoProvider {
    fun current(): SidekickBuildInfo
}

internal class AndroidResourceSidekickBuildInfoProvider(
    private val context: Context,
) : SidekickBuildInfoProvider {
    override fun current(): SidekickBuildInfo = parseSidekickBuildInfo(
        buildEpochMs = context.getString(R.string.fixthis_sidekick_build_epoch_ms),
        gitSha = context.getString(R.string.fixthis_sidekick_git_sha),
    )
}

internal fun parseSidekickBuildInfo(
    buildEpochMs: String,
    gitSha: String,
): SidekickBuildInfo = SidekickBuildInfo(
    buildEpochMs = buildEpochMs.toLongOrNull() ?: 0L,
    gitSha = gitSha.ifBlank { "unknown" },
)
