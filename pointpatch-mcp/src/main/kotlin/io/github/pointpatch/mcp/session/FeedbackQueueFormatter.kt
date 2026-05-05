package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson

object FeedbackQueueFormatter {
    fun toJson(session: FeedbackSession): String =
        pointPatchJson.encodeToString(FeedbackSession.serializer(), session)

    fun toMarkdown(session: FeedbackSession): String = buildString {
        appendLine("# PointPatch Feedback Queue")
        appendLine()
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Status: `${session.status.name.lowercase()}`")
        appendLine("- Screens: `${session.screens.size}`")
        appendLine("- Feedback Items: `${session.items.size}`")
        appendLine("- Handoff Batches: `${session.handoffBatches.size}`")
        appendLine("- Updated At: `${session.updatedAtEpochMillis}`")
        appendLine()
        appendLine("> Screenshots are local debug artifacts. Review them before sharing exported content.")
        appendLine()

        appendLine("## Referenced Screens")
        appendLine()
        val referencedScreenIds = session.items.map { it.screenId }.toSet()
        val referencedScreens = session.screens.filter { it.screenId in referencedScreenIds }
        if (referencedScreens.isEmpty()) {
            appendLine("No referenced screens.")
            appendLine()
        } else {
            referencedScreens.forEach { screen ->
                appendLine("### ${screenLabel(session, screen.screenId)}")
                appendLine()
                screen.activityName?.let { appendLine("- Activity: `$it`") }
                appendLine("- Captured At: `${screen.capturedAtEpochMillis}`")
                if (screen.screenshot != null) appendLine("- Screenshot: local/debug artifact available through PointPatch tooling")
                screen.screenshot?.dimensionsLabel()?.let { appendLine("- Screenshot Size: `$it`") }
                appendLine("- Feedback Count: `${session.items.count { it.screenId == screen.screenId }}`")
                appendLine()
            }
        }

        appendLine("## Draft")
        appendLine()
        val draftItems = session.items.filter { it.delivery == FeedbackDelivery.DRAFT }
        if (draftItems.isEmpty()) {
            appendLine("No draft feedback items.")
            appendLine()
        } else {
            draftItems.forEach { item -> appendFeedbackItem(session, item) }
        }

        appendLine("## Sent History")
        appendLine()
        val sentItems = session.items.filter { it.delivery == FeedbackDelivery.SENT }
        val batchesById = session.handoffBatches.associateBy { it.batchId }
        if (sentItems.isEmpty() && session.handoffBatches.isEmpty()) {
            appendLine("No sent handoff batches.")
            appendLine()
        } else {
            session.handoffBatches.sortedBy { it.sequenceNumber }.forEach { batch ->
                appendLine("### Batch #${batch.sequenceNumber}")
                appendLine()
                appendLine("- Sent At: `${batch.createdAtEpochMillis}`")
                appendLine("- Item Count: `${batch.itemIds.size}`")
                appendLine()
                sentItems.filter { it.handoffBatchId == batch.batchId }
                    .forEach { item -> appendFeedbackItem(session, item) }
                batch.itemIds.filter { itemId -> session.items.none { it.itemId == itemId } }
                    .forEach { appendMissingFeedbackItem() }
            }

            sentItems.filter { item ->
                item.handoffBatchId == null || !batchesById.containsKey(item.handoffBatchId)
            }.takeIf { it.isNotEmpty() }?.let { missingBatchItems ->
                appendLine("### Unbatched / missing batch")
                appendLine()
                missingBatchItems.forEach { item -> appendFeedbackItem(session, item) }
            }
        }
    }

    private fun StringBuilder.appendMissingFeedbackItem() {
        appendLine("- Missing feedback item metadata.")
        appendLine()
    }

    private fun StringBuilder.appendFeedbackItem(session: FeedbackSession, item: FeedbackItem) {
        val number = item.sequenceNumber?.let { "#$it " }.orEmpty()
        appendLine("### $number${item.comment.lineSequence().firstOrNull().orEmpty().ifBlank { "(No comment)" }}")
        appendLine()
        appendLine("- Delivery: `${item.delivery.name.lowercase()}`")
        appendLine("- Status: `${item.status.name.lowercase()}`")
        val screen = session.screens.firstOrNull { it.screenId == item.screenId }
        appendLine("- Screen: `${screenLabel(session, item.screenId)}`")
        screen?.activityName?.let { appendLine("- Activity: `$it`") }
        screen?.let { appendLine("- Captured At: `${it.capturedAtEpochMillis}`") }
        if (screen?.screenshot != null) appendLine("- Screenshot: local/debug artifact available through PointPatch tooling")
        screen?.screenshot?.dimensionsLabel()?.let { appendLine("- Screenshot Size: `$it`") }
        appendLine("- Target: `${item.target.describe()}`")
        item.selectedNode?.let { node ->
            if (node.text.isNotEmpty()) appendLine("- Selected Text: `${node.text.joinToString(" | ")}`")
            if (node.contentDescription.isNotEmpty()) {
                appendLine("- Selected Content Description: `${node.contentDescription.joinToString(" | ")}`")
            }
        }
        item.sourceCandidates.firstOrNull()?.let { candidate ->
            appendLine("- Source Candidate: `${candidate.file}${candidate.line?.let { line -> ":$line" }.orEmpty()}`")
        }
        appendLine()
        appendLine(item.comment.ifBlank { "(No comment)" })
        appendLine()
    }

    private fun screenLabel(session: FeedbackSession, screenId: String): String {
        val index = session.screens.indexOfFirst { it.screenId == screenId }
        val screen = session.screens.getOrNull(index)
        return if (screen == null) {
            "Unknown screen"
        } else {
            "Screen ${index + 1} - ${screen.displayName}"
        }
    }

    private fun FeedbackTarget.describe(): String =
        when (this) {
            is FeedbackTarget.Area -> "area bounds ${boundsInWindow.left},${boundsInWindow.top},${boundsInWindow.right},${boundsInWindow.bottom}"
            is FeedbackTarget.Node -> "node bounds ${boundsInWindow.left},${boundsInWindow.top},${boundsInWindow.right},${boundsInWindow.bottom}"
        }

    private fun FeedbackScreenshot.dimensionsLabel(): String? =
        if (width != null && height != null) "${width}x$height" else null
}
