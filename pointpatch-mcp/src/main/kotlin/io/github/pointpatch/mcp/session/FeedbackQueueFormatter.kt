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

        session.screens.forEach { screen ->
            appendLine("## Screen: ${screen.displayName}")
            appendLine()
            appendLine("- Screen ID: `${screen.screenId}`")
            screen.activityName?.let { appendLine("- Activity: `$it`") }
            screen.screenshot?.desktopFullPath?.let { appendLine("- Screenshot: `$it`") }

            val items = session.items.filter { it.screenId == screen.screenId }
            if (items.isEmpty()) {
                appendLine()
                appendLine("No feedback items for this screen.")
                appendLine()
            } else {
                items.forEachIndexed { index, item ->
                    appendLine()
                    appendLine("### ${index + 1}. ${item.comment.lineSequence().firstOrNull().orEmpty().ifBlank { "(No comment)" }}")
                    appendLine()
                    appendLine("- Item ID: `${item.itemId}`")
                    appendLine("- Status: `${item.status.name.lowercase()}`")
                    appendLine("- Target: `${item.target.describe()}`")
                    item.sourceCandidates.firstOrNull()?.let { candidate ->
                        appendLine("- Source candidate: `${candidate.file}${candidate.line?.let { line -> ":$line" }.orEmpty()}`")
                    }
                    appendLine()
                    appendLine(item.comment.ifBlank { "(No comment)" })
                }
                appendLine()
            }
        }
    }

    private fun FeedbackTarget.describe(): String =
        when (this) {
            is FeedbackTarget.Area -> "area ${boundsInWindow.left},${boundsInWindow.top},${boundsInWindow.right},${boundsInWindow.bottom}"
            is FeedbackTarget.Node -> "node $nodeUid"
        }
}
