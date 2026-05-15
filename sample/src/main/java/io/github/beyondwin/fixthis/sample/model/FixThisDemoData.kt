package io.github.beyondwin.fixthis.sample.model

enum class FeedbackSeverity(val label: String) {
    Critical("Critical"),
    High("High"),
    Medium("Medium"),
    Low("Low"),
}

enum class FeedbackState(val label: String) {
    New("New"),
    Triaged("Triaged"),
    InReview("In review"),
    Blocked("Blocked"),
    Resolved("Resolved"),
}

data class FeedbackItem(
    val id: String,
    val title: String,
    val screenName: String,
    val severity: FeedbackSeverity,
    val state: FeedbackState,
    val assignee: String,
    val ageLabel: String,
    val summary: String,
    val captureLabel: String,
    val sourceConfidence: String,
)

data class ProjectMetric(
    val label: String,
    val value: String,
    val trendLabel: String,
    val state: FeedbackState,
)

data class ActivityEvent(
    val title: String,
    val detail: String,
    val timeLabel: String,
    val category: String,
)

data class DiagnosticSignal(
    val label: String,
    val value: String,
    val state: FeedbackState,
    val trendLabel: String,
)

object FixThisDemoData {
    const val productName = "FixThis Studio"
    const val projectSummary = "Mobile checkout polish"

    val metrics = listOf(
        ProjectMetric("Open feedback", "28", "+6 today", FeedbackState.New),
        ProjectMetric("High priority", "7", "3 need owner", FeedbackState.Blocked),
        ProjectMetric("Resolved this week", "19", "+12%", FeedbackState.Resolved),
        ProjectMetric("Queued agent drafts", "11", "ready to send", FeedbackState.InReview),
    )

    val feedbackItems = listOf(
        FeedbackItem(
            id = "FX-1042",
            title = "Primary purchase action blends into the summary panel",
            screenName = "Payment summary",
            severity = FeedbackSeverity.Critical,
            state = FeedbackState.New,
            assignee = "Mina",
            ageLabel = "12 min",
            summary = "Increase contrast and make the pay action easier to target from the bottom bar.",
            captureLabel = "Bottom bar capture",
            sourceConfidence = "92%",
        ),
        FeedbackItem(
            id = "FX-1038",
            title = "Filter chips wrap awkwardly on narrow devices",
            screenName = "Catalog",
            severity = FeedbackSeverity.High,
            state = FeedbackState.Triaged,
            assignee = "Jules",
            ageLabel = "43 min",
            summary = "Keep selected filters visible while preserving tap targets at large font scale.",
            captureLabel = "Filter rail capture",
            sourceConfidence = "87%",
        ),
        FeedbackItem(
            id = "FX-1029",
            title = "Close confirmation copy is too terse for destructive action",
            screenName = "Project detail",
            severity = FeedbackSeverity.Medium,
            state = FeedbackState.InReview,
            assignee = "Sam",
            ageLabel = "2 hr",
            summary = "Clarify what closes, what remains in history, and how reviewers can reopen it.",
            captureLabel = "Dialog copy capture",
            sourceConfidence = "81%",
        ),
        FeedbackItem(
            id = "FX-1017",
            title = "Health chart has no meaningful target for visual feedback",
            screenName = "Diagnostics",
            severity = FeedbackSeverity.Low,
            state = FeedbackState.Blocked,
            assignee = "No owner",
            ageLabel = "1 day",
            summary = "Keep the visual chart selectable with area selection while labeling the timeline.",
            captureLabel = "Canvas region capture",
            sourceConfidence = "64%",
        ),
    )

    val activity = listOf(
        ActivityEvent("Mina assigned FX-1042", "Payment contrast feedback moved to priority queue.", "Now", "Triage"),
        ActivityEvent("Agent draft prepared", "Review request includes screenshot context and source hints.", "18 min", "Handoff"),
        ActivityEvent("Sam reopened FX-1029", "Close confirmation copy needs one more pass.", "1 hr", "Review"),
    )

    val diagnostics = listOf(
        DiagnosticSignal("Selection confidence", "92%", FeedbackState.Resolved, "+4 points"),
        DiagnosticSignal("Weak semantic regions", "3", FeedbackState.InReview, "2 expected"),
        DiagnosticSignal("Waiting for device preview", "2s", FeedbackState.Blocked, "ADB pending"),
    )

    val queueFilters = listOf("Critical", "Assigned to me", "Needs screenshot", "Waiting")
}
