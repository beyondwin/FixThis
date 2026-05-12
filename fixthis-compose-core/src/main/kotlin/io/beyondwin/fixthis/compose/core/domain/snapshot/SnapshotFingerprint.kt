package io.beyondwin.fixthis.compose.core.domain.snapshot

import java.security.MessageDigest

object SnapshotFingerprint {
    private const val FINGERPRINT_BYTE_LENGTH = 8

    fun compute(snapshot: Snapshot): String? {
        val components =
            listOf(
                snapshot.activityName.orEmpty(),
                snapshot.orientation?.name.orEmpty(),
                "${snapshot.widthPx}×${snapshot.heightPx}@${snapshot.densityDpi}",
                snapshot.windowMode?.name.orEmpty(),
                snapshot.systemUiKind.orEmpty(),
            )
        if (components.all { it.isEmpty() || it.contains("null") }) return null
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(components.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.take(FINGERPRINT_BYTE_LENGTH).joinToString("") { "%02x".format(it) }
    }
}
