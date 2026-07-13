package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

internal object LegacyRuntimeEvidenceReplay {
    fun restoreSnapshotOnlyItemLinks(
        snapshot: SessionDto,
        replayed: SessionDto,
    ): SessionDto {
        val linksByItemId = snapshot.items
            .filter { it.runtimeEvidenceIds.isNotEmpty() }
            .associate { it.itemId to it.runtimeEvidenceIds }
        if (linksByItemId.isEmpty()) return replayed
        return replayed.copy(
            items = replayed.items.map { item ->
                val legacyLinks = linksByItemId[item.itemId].orEmpty()
                if (legacyLinks.isEmpty()) {
                    item
                } else {
                    item.copy(
                        runtimeEvidenceIds = (item.runtimeEvidenceIds + legacyLinks).distinct(),
                    )
                }
            },
        )
    }
}
