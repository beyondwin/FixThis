package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventJournal
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionEventPayloadFactory
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutation
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionReducer
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionCompactionCoordinator
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.verificationReceipt
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationRequestValidator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationStartContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val VERIFICATION_CONTEXT_CHANGED_PREFIX = "VERIFICATION_CONTEXT_CHANGED:"

internal class VerificationReceiptStoreMutations(
    private val lock: ReentrantLock,
    private val clock: () -> Long,
    private val stateStore: SessionStateStore,
    private val journal: SessionEventJournal,
    private val compactionCoordinator: SessionCompactionCoordinator,
    private val requestValidator: FeedbackVerificationRequestValidator = FeedbackVerificationRequestValidator(),
) {
    private val sessionUpdatedListeners = CopyOnWriteArrayList<(SessionDto) -> Unit>()

    fun subscribeSessionUpdates(listener: (SessionDto) -> Unit): AutoCloseable {
        sessionUpdatedListeners += listener
        return AutoCloseable { sessionUpdatedListeners -= listener }
    }

    fun captureContext(
        sessionId: String,
        itemId: String,
        assertions: List<FeedbackVerificationAssertionDto>,
    ): FeedbackVerificationStartContext = lock.withLock {
        requestValidator.validate(getSession(sessionId), itemId, assertions)
    }

    fun validateContext(context: FeedbackVerificationStartContext) {
        lock.withLock {
            requireUnchanged(getSession(context.sessionId), context)
        }
    }

    fun attach(
        context: FeedbackVerificationStartContext,
        receipt: FeedbackVerificationReceiptDto,
    ): SessionDto {
        val updated = lock.withLock {
            val session = getSession(context.sessionId)
            requireUnchanged(session, context)
            val next = SessionReducer.reduce(
                session,
                SessionMutation.AttachVerificationReceipt(receipt, clock()),
            )
            journal.append(
                sessionId = context.sessionId,
                type = "feedbackVerified",
                payload = SessionEventPayloadFactory.verificationReceipt(context.sessionId, receipt),
            )
            stateStore.commit(session, next)
        }
        compactionCoordinator.compactAfterMutation(context.sessionId)
        sessionUpdatedListeners.forEach { listener -> runCatching { listener(updated) } }
        return updated
    }

    fun contextChanged(): FeedbackSessionException = FeedbackSessionException(
        "$VERIFICATION_CONTEXT_CHANGED_PREFIX Feedback changed during verification",
    )

    private fun getSession(sessionId: String): SessionDto = try {
        stateStore.get(sessionId)
    } catch (error: FeedbackSessionException) {
        if (error.message.orEmpty().startsWith("Unknown feedback session:")) throw contextChanged()
        throw error
    }

    private fun requireUnchanged(
        current: SessionDto,
        context: FeedbackVerificationStartContext,
    ) {
        val item = current.items.singleOrNull { it.itemId == context.item.itemId }
        val unchanged = current.status != SessionStatusDto.CLOSED &&
            current.updatedAtEpochMillis == context.sessionUpdatedAtEpochMillis &&
            item?.status == AnnotationStatusDto.IN_PROGRESS &&
            item.updatedAtEpochMillis == context.itemUpdatedAtEpochMillis &&
            current.screens.any { it.screenId == context.baselineScreen.screenId } &&
            current.verificationReceipts.size == context.receiptCount
        if (!unchanged) throw contextChanged()
    }
}
