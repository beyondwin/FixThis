package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson

object FeedbackQueueFormatter {
    fun toJson(session: FeedbackSession): String =
        pointPatchJson.encodeToString(FeedbackSession.serializer(), session)

    fun toMarkdown(session: FeedbackSession): String = buildString {
        appendLine("# PointPatch Feedback Queue")
        appendLine()
        appendLine("- Session: `${session.sessionId}`")
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Status: `${session.status.name.lowercase()}`")
        appendLine()
        appendLine("> Screenshots are local debug artifacts. Review them before sharing exported content.")
        appendLine()

        appendLine("## Screens")
        appendLine()
        if (session.screens.isEmpty()) {
            appendLine("No captured screens.")
            appendLine()
        } else {
            session.screens.forEachIndexed { index, screen ->
                appendLine("### Screen ${index + 1} - ${screen.displayName}")
                appendLine()
                appendLine("- Screen ID: `${screen.screenId}`")
                screen.activityName?.let { appendLine("- Activity: `$it`") }
                screen.screenshot?.desktopFullPath?.let { appendLine("- Screenshot: `$it`") }
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
                appendLine("- Batch ID: `${batch.batchId}`")
                appendLine("- Sent At: `${batch.createdAtEpochMillis}`")
                appendLine("- Item Count: `${batch.itemIds.size}`")
                appendLine()
                sentItems.filter { it.handoffBatchId == batch.batchId }
                    .forEach { item -> appendFeedbackItem(session, item) }
                batch.itemIds.filter { itemId -> session.items.none { it.itemId == itemId } }
                    .forEach { itemId -> appendMissingFeedbackItem(itemId) }
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

    private fun StringBuilder.appendMissingFeedbackItem(itemId: String) {
        appendLine("- Missing feedback item: `$itemId`")
        appendLine()
    }

    private fun StringBuilder.appendFeedbackItem(session: FeedbackSession, item: FeedbackItem) {
        val number = item.sequenceNumber?.let { "#$it " }.orEmpty()
        appendLine("### $number${item.comment.lineSequence().firstOrNull().orEmpty().ifBlank { "(No comment)" }}")
        appendLine()
        appendLine("- Item ID: `${item.itemId}`")
        appendLine("- Delivery: `${item.delivery.name.lowercase()}`")
        item.handoffBatchId?.let { appendLine("- Handoff Batch: `$it`") }
        appendLine("- Status: `${item.status.name.lowercase()}`")
        appendLine("- Screen: `${screenLabel(session, item.screenId)}`")
        appendLine("- Target: `${item.target.describe()}`")
        item.selectedNode?.let { node ->
            appendLine("- Selected Node: `${node.uid}`")
            if (node.text.isNotEmpty()) appendLine("- Selected Text: `${node.text.joinToString(" | ")}`")
            if (node.contentDescription.isNotEmpty()) {
                appendLine("- Selected Content Description: `${node.contentDescription.joinToString(" | ")}`")
            }
        }
        item.sourceCandidates.firstOrNull()?.let { candidate ->
            appendLine("- Source candidate: `${candidate.file}${candidate.line?.let { line -> ":$line" }.orEmpty()}`")
        }
        appendLine()
        appendLine(item.comment.ifBlank { "(No comment)" })
        appendLine()
    }

    private fun screenLabel(session: FeedbackSession, screenId: String): String {
        val index = session.screens.indexOfFirst { it.screenId == screenId }
        val screen = session.screens.getOrNull(index)
        return if (screen == null) {
            screenId
        } else {
            "Screen ${index + 1} - ${screen.displayName}"
        }
    }

    private fun FeedbackTarget.describe(): String =
        when (this) {
            is FeedbackTarget.Area -> "area ${boundsInWindow.left},${boundsInWindow.top},${boundsInWindow.right},${boundsInWindow.bottom}"
            is FeedbackTarget.Node -> "node $nodeUid"
        }
}
