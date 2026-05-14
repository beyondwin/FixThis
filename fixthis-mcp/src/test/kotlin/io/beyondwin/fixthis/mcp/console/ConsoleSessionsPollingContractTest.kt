/**
 * JS-source contract tests for the sessions-polling subsystem.
 *
 * These tests read the bundled `console/app.js` resource as a UTF-8 string and
 * assert that specific function names and literal constants exist in expected
 * positions. They do not exercise HTTP routes.
 *
 * Source modules under test:
 * - fixthis-mcp/src/main/console/sessions-polling.js (poll loop, backoff,
 *   visibility recovery)
 * - fixthis-mcp/src/main/console/state.js (withMutationLock,
 *   mergeSessionIntoState helpers)
 * - fixthis-mcp/src/main/console/history.js (historyPip rendering)
 * - fixthis-mcp/src/main/console/prompt.js (promptActions guard)
 *
 * If a bundle-optimization or state-machine refactor renames any of these
 * symbols, update the assertions here in lockstep.
 */
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleSessionsPollingContractTest {
    @Test
    fun historyPipsCollapseWorkingIntoOpen() {
        val html = ConsoleSourceFixtures.readAll()
        assertFalse(
            html.contains("class=\"hi-pip working\""),
            "History pips must not render a separate working/WIP pip",
        )
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        assertTrue(
            historyOpenCount.contains("(session.inProgressItemsCount || 0)"),
            "historyOpenCount must include in-progress items so WIP collapses into the open count",
        )
    }

    @Test
    fun historyPipDropsPointsLabel() {
        val html = ConsoleSourceFixtures.readAll()
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("hi-pip points"), "Points pip must be removed")
    }

    @Test
    fun promptActionsDoNotSilentlyDropUncommentedPendingAnnotations() {
        val html = ConsoleSourceFixtures.readAll()
        val persistAndCollect = javascriptFunctionBody(html, "persistAndCollectItemIds")

        assertTrue(
            persistAndCollect.contains("draftFeedbackItems.some(item => !hasWrittenAnnotationComment(item))"),
            "Prompt actions must detect partially-commented pending batches before persistence",
        )
        assertTrue(
            persistAndCollect.contains("Add a comment to every annotation before saving."),
            "Prompt actions must keep all pending annotations visible instead of saving a partial batch",
        )
        assertFalse(
            persistAndCollect.contains("persistPendingFeedbackItems({ onlyWrittenComments: true })"),
            "Persisting only written comments clears the entire pending flow and silently drops uncommented pins",
        )
    }

    @Test
    fun mutationsAreWrappedInLock() {
        val html = ConsoleSourceFixtures.readAll()
        val sendAgent = javascriptFunctionBody(html, "sendAgentPrompt")
        val copyPrompt = javascriptFunctionBody(html, "copyPrompt")
        assertTrue(sendAgent.contains("withMutationLock"))
        assertTrue(copyPrompt.contains("withMutationLock"))
    }

    @Test
    fun mergeSessionIntoStatePreservesUserState() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(body.contains("comment.value"), "Must preserve textarea value")
        assertTrue(body.contains("focusedSavedItemId") || body.contains("focusedPendingItemIndex"))
        assertTrue(body.contains("currentSelection"))
        assertTrue(body.contains("data-just-changed"))
    }

    @Test
    fun mergeSessionIntoStateSkipsHighlightOnBulkChange() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(
            body.contains("BULK_CHANGE_HIGHLIGHT_THRESHOLD") || body.contains(">= 6") || body.contains("> 5"),
            "mergeSessionIntoState must guard against bulk highlight cascade",
        )
    }

    @Test
    fun startSessionsPollingIsCalledOnBoot() {
        val html = ConsoleSourceFixtures.readAll()
        // Boot chain (16-space indent inside .then()): startSessionsPolling() must follow
        // startLivePreviewPolling() in the .then() block that already starts heartbeat + live-preview polling.
        assertTrue(
            html.contains(
                "                startHeartbeatPolling();\n" +
                    "                startLivePreviewPolling();\n" +
                    "                startSessionsPolling();\n" +
                    "              })",
            ),
            "main.js boot chain must call startSessionsPolling() after startLivePreviewPolling()",
        )
        // Visibility-change handler (14-space indent inside arrow body): must restart sessions polling
        // alongside the live-preview polling restart when the tab becomes visible again.
        assertTrue(
            html.contains(
                "              startLivePreviewPolling();\n" +
                    "              startSessionsPolling();\n" +
                    "            });",
            ),
            "visibilitychange handler must restart startSessionsPolling() when tab becomes visible",
        )
    }

    @Test
    fun sessionsPollingDeclaresFailureBackoffConstants() {
        // Failure counter (consecutiveFailures) and threshold
        // (MaxConsecutivePollFailures = 5) now live in pollingFsm.js. The
        // counter no longer exists as a module-level let in state.js, but
        // both identifiers must survive in the bundle as part of the FSM.
        val html = ConsoleSourceFixtures.readAll()
        assertTrue(html.contains("consecutiveFailures"), "polling FSM must own the failure counter")
        assertTrue(
            html.contains("MaxConsecutivePollFailures = 5") || html.contains("MaxConsecutivePollFailures=5"),
            "must declare threshold constant",
        )
    }

    @Test
    fun pollSessionsTickResetsFailureCounterOnSuccess() {
        // The reset semantics now live in pollingFsm.js TICK_OK. The
        // top-level pollSessionsTick wrapper still exists for the grep
        // contract; the FSM-side behavior is verified by node tests.
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("pollingUseCases.pollSessionsTick"),
            "tick must delegate to pollingUseCases.pollSessionsTick (FSM dispatches TICK_OK on success)",
        )
    }

    @Test
    fun pollSessionsTickIncrementsFailureCounterOnError() {
        // Increment semantics live in pollingFsm.js TICK_FAILED.
        val html = ConsoleSourceFixtures.readAll()
        assertTrue(
            html.contains("TICK_FAILED"),
            "polling FSM must dispatch TICK_FAILED to increment the failure counter",
        )
    }

    @Test
    fun pollSessionsTickPausesAfterThreshold() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("setSessionsPollingPaused(true)") || body.contains("stopSessionsPolling()"),
            "tick must pause polling once threshold reached",
        )
    }

    @Test
    fun visibilityChangeRecoversFromPolledFailure() {
        val html = ConsoleSourceFixtures.readAll()
        // The visibilitychange handler must restart polling when paused.
        assertTrue(
            html.contains("sessionsPollingPaused") && html.contains("startSessionsPolling"),
            "visibility handler must consult sessionsPollingPaused and call startSessionsPolling",
        )
    }

    @Test
    fun withMutationLockRecoversFromPolledFailure() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "withMutationLock")
        assertTrue(
            body.contains("sessionsPollingPaused") || body.contains("startSessionsPolling"),
            "withMutationLock finally-block must restart polling if paused",
        )
    }
}
