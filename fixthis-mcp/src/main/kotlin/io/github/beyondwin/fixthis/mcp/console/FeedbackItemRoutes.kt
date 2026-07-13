package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.PreviewFeedbackFingerprintCheck
import io.github.beyondwin.fixthis.mcp.session.PreviewFeedbackLiveSaveRequest
import io.github.beyondwin.fixthis.mcp.session.draft.DraftSaveService
import io.github.beyondwin.fixthis.mcp.session.draft.PreviewFeedbackRequestValidationException
import io.github.beyondwin.fixthis.mcp.session.draft.StaleDraftRevisionException
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.preview.ScreenFingerprintMismatch
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureRequest
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceCaptureResult
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val UNPROCESSABLE_ENTITY = 422

internal class FeedbackItemRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
    private val draftSaveService: DraftSaveService = DraftSaveService(),
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/items" ||
        path == "/api/items/batch" ||
        path == "/api/items/draft" ||
        path.startsWith("/api/items/") ||
        path == "/api/agent-handoffs"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/items" -> exchange.requireMethod("POST") {
                val request = exchange.decodeAddFeedbackItemBody()
                request.validateComment()
                val sessionId = requestSessionId(request.sessionId)
                val item = try {
                    runBlocking {
                        service.addFeedbackItem(
                            sessionId = sessionId,
                            screenId = request.screenId,
                            targetType = request.targetType,
                            bounds = request.bounds,
                            nodeUid = request.nodeUid,
                            comment = request.comment,
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    throw FeedbackConsoleHttpException(400, error.message ?: "Invalid feedback item request")
                }
                eventBus.emitSessionUpdated(service.getSession(sessionId))
                exchange.sendJson(200, item)
            }
            "/api/items/batch" -> exchange.requireMethod("POST") {
                val request = exchange.decodeSavePreviewFeedbackItemsBody()
                val sessionId = requestSessionId(request.sessionId)
                val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                try {
                    draftSaveService.validateIfMatch(sessionId, ifMatch)
                } catch (error: StaleDraftRevisionException) {
                    exchange.sendStaleDraftConflict(sessionId, error.currentRev)
                    return@requireMethod
                }
                val result = try {
                    request.validateComments()
                    runBlocking {
                        service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                            PreviewFeedbackLiveSaveRequest(
                                sessionId = sessionId,
                                previewId = request.previewId,
                                workspaceId = request.workspaceId,
                                items = request.items,
                                fallbackScreen = request.screen,
                                fingerprintCheck = PreviewFeedbackFingerprintCheck(
                                    frozenFingerprint = request.frozenFingerprint,
                                    forceMismatchOverride = request.forceMismatchOverride,
                                ),
                            ),
                        )
                    }
                } catch (error: PreviewFeedbackRequestValidationException) {
                    throw error.toConsoleHttpException()
                } catch (error: ScreenFingerprintMismatch) {
                    exchange.sendJson(
                        HTTP_STATUS_CONFLICT,
                        buildJsonObject {
                            put("error", "screen_fingerprint_mismatch")
                            put("frozenFingerprint", error.frozenFingerprint)
                            put("currentFingerprint", error.currentFingerprint)
                        },
                    )
                    return@requireMethod
                }
                result.fingerprintUnavailableReason?.let { reason ->
                    exchange.responseHeaders.set("X-FixThis-Fingerprint-Unavailable-Reason", reason)
                }
                val nextRev = draftSaveService.bumpRev(sessionId)
                exchange.responseHeaders.set("ETag", "\"$nextRev\"")
                eventBus.emitSessionUpdated(result.session)
                exchange.sendJson(200, result.session)
            }
            "/api/items/draft" -> exchange.requireMethod("DELETE") {
                val sessionId = exchange.queryParameter("sessionId")?.takeIf { it.isNotBlank() }
                    ?: service.requireCurrentSession().sessionId
                val session = service.clearDraftItems(sessionId)
                eventBus.emitSessionUpdated(session)
                exchange.sendJson(200, session)
            }
            "/api/agent-handoffs" -> exchange.requireMethod("POST") {
                val request = exchange.decodeAgentHandoffBody()
                if (request.itemIds.isEmpty()) {
                    throw FeedbackConsoleHttpException(400, "itemIds must not be empty (legacy {prompt} body is no longer accepted; use {itemIds:[...]})")
                }
                val sessionId = requestSessionId(request.sessionId)
                val result = runBlocking {
                    service.sendDraftToAgentWithRuntimeEvidence(sessionId, request.itemIds)
                }
                eventBus.emitSessionUpdated(result.session)
                exchange.sendJson(
                    200,
                    AgentHandoffResponse(
                        session = result.session,
                        prompt = result.prompt,
                        runtimeEvidence = result.runtimeEvidence,
                    ),
                )
            }
            else -> {
                val path = exchange.requestURI.path
                if (!path.startsWith("/api/items/")) return
                val collectMatch = runtimeEvidenceCollectPath.matchEntire(path)
                if (collectMatch != null) {
                    exchange.requireMethod("POST") {
                        val itemId = collectMatch.groupValues[ITEM_ID_CAPTURE_GROUP]
                        val request = exchange.decodeJsonBody(
                            CollectRuntimeEvidenceRequest.serializer(),
                            blankValue = CollectRuntimeEvidenceRequest(),
                        )
                        val sessionId = requestSessionId(request.sessionId)
                        val session = service.getSession(sessionId)
                        if (session.status == SessionStatusDto.CLOSED) {
                            throw FeedbackConsoleHttpException(
                                HTTP_STATUS_CONFLICT,
                                "Feedback session is closed",
                                "session_closed",
                            )
                        }
                        val item = session.items.firstOrNull { it.itemId == itemId }
                            ?: throw FeedbackConsoleHttpException(404, "Feedback item not found", "unknown_feedback_item")
                        val result = if (session.runtimeEvidencePolicy == RuntimeEvidencePolicy.OFF) {
                            RuntimeEvidenceCaptureResult.skipped("runtime_evidence_disabled")
                        } else {
                            runBlocking {
                                service.collectRuntimeEvidence(
                                    RuntimeEvidenceCaptureRequest(
                                        sessionId = sessionId,
                                        itemIds = listOf(itemId),
                                        screenId = item.screenId,
                                        preset = request.preset,
                                        trigger = RuntimeEvidenceTrigger.CONSOLE_MANUAL,
                                    ),
                                )
                            }
                        }
                        eventBus.emitSessionUpdated(service.getSession(sessionId))
                        exchange.sendJson(
                            200,
                            fixThisJson.encodeToJsonElement(RuntimeEvidenceCaptureResult.serializer(), result).jsonObject,
                        )
                    }
                } else if (exchange.requestURI.path.endsWith("/runtime-evidence")) {
                    exchange.requireMethod("POST") {
                        val itemId = exchange.requestURI.path
                            .removePrefix("/api/items/")
                            .removeSuffix("/runtime-evidence")
                            .trim('/')
                            .takeIf { it.isNotBlank() }
                            ?: throw FeedbackConsoleHttpException(404, "Feedback item not found")
                        val request = exchange.decodeRuntimeEvidenceBody()
                        val sessionId = requestSessionId(request.sessionId)
                        val summary = request.summary?.takeIf { it.isNotBlank() }
                            ?: throw FeedbackConsoleHttpException(
                                UNPROCESSABLE_ENTITY,
                                "runtime evidence summary is required",
                            )
                        val session = service.captureRuntimeEvidence(
                            sessionId = sessionId,
                            itemId = itemId,
                            type = request.type,
                            summary = summary,
                            artifactPath = request.artifactPath,
                        )
                        eventBus.emitSessionUpdated(session)
                        exchange.sendJson(200, session)
                    }
                } else {
                    val itemId = exchange.requestURI.path.removePrefix("/api/items/")
                        .takeIf { it.isNotBlank() }
                        ?: throw FeedbackConsoleHttpException(404, "Feedback item not found")
                    when (exchange.requestMethod) {
                        "PUT" -> {
                            val request = exchange.decodeUpdateFeedbackItemBody()
                            val session = service.updateDraftFeedback(
                                sessionId = request.sessionId?.takeIf { it.isNotBlank() }
                                    ?: service.requireCurrentSession().sessionId,
                                itemId = itemId,
                                label = request.label,
                                severity = request.severity,
                                comment = request.comment,
                                status = request.status,
                            )
                            eventBus.emitSessionUpdated(session)
                            exchange.sendJson(200, session)
                        }
                        "DELETE" -> {
                            val session = service.deleteDraftFeedback(
                                sessionId = exchange.queryParameter("sessionId")?.takeIf { it.isNotBlank() }
                                    ?: service.requireCurrentSession().sessionId,
                                itemId = itemId,
                            )
                            eventBus.emitSessionUpdated(session)
                            exchange.sendJson(
                                200,
                                session,
                            )
                        }
                        else -> exchange.requireMethod("PUT") {}
                    }
                }
            }
        }
    }

    private fun HttpExchange.decodeAddFeedbackItemBody(): AddAnnotationRequest {
        val body = requestBodyText()
        return runCatching {
            val jsonObject = fixThisJson.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Feedback item request body must be a JSON object")
            val unsupportedKey = jsonObject.keys.firstOrNull { it !in allowedAddFeedbackItemRequestKeys }
            if (unsupportedKey != null) {
                throw IllegalArgumentException("Unsupported feedback item field: $unsupportedKey")
            }
            fixThisJson.decodeFromString(AddAnnotationRequest.serializer(), body)
        }.getOrElse { error ->
            throw FeedbackConsoleHttpException(400, error.message ?: "Invalid JSON request body")
        }
    }

    private fun HttpExchange.decodeSavePreviewFeedbackItemsBody(): SaveSnapshotRequest = decodeJsonBody(SaveSnapshotRequest.serializer())

    private fun HttpExchange.decodeUpdateFeedbackItemBody(): UpdateAnnotationRequest = decodeJsonBody(UpdateAnnotationRequest.serializer())

    private fun HttpExchange.decodeRuntimeEvidenceBody(): RuntimeEvidenceRequest = decodeJsonBody(RuntimeEvidenceRequest.serializer())

    private fun HttpExchange.decodeAgentHandoffBody(): AgentHandoffRequest = decodeJsonBody(AgentHandoffRequest.serializer(), blankValue = AgentHandoffRequest())

    private fun requestSessionId(explicit: String?): String = explicit?.takeIf { it.isNotBlank() } ?: currentId()

    private fun currentId(): String = service.requireCurrentSession().sessionId

    private fun HttpExchange.sendStaleDraftConflict(sessionId: String, currentRev: String) {
        val serverDraft: SessionDto? = runCatching { service.getSession(sessionId) }.getOrNull()
        val readiness = FirstRunReadinessCatalog.stalePreview(
            cause = "A newer save advanced the draft revision since this client last synced.",
            details = mapOf("currentRev" to currentRev),
        )
        responseHeaders.set("ETag", "\"$currentRev\"")
        val body = buildJsonObject {
            put("state", "STALE_PREVIEW")
            put("readiness", fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), readiness))
            if (serverDraft != null) {
                put("serverDraft", fixThisJson.encodeToJsonElement(SessionDto.serializer(), serverDraft))
            }
        }
        sendJson(HTTP_STATUS_PRECONDITION_FAILED, body)
    }
}

private fun AddAnnotationRequest.validateComment() {
    if (comment.isBlank()) {
        throw FeedbackConsoleHttpException(
            statusCode = 422,
            message = "Cannot save annotation with empty comment.",
            errorCode = "empty-comment",
            action = null,
        )
    }
}

private fun SaveSnapshotRequest.validateComments() {
    val empties = items.count { it.comment.isBlank() }
    if (empties > 0) {
        throw PreviewFeedbackRequestValidationException(
            "Cannot save annotation with empty comment ($empties item(s)).",
        )
    }
}

private val allowedAddFeedbackItemRequestKeys = setOf(
    "sessionId",
    "screenId",
    "comment",
    "targetType",
    "bounds",
    "nodeUid",
)

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNPROCESSABLE_ENTITY = 422

private fun PreviewFeedbackRequestValidationException.toConsoleHttpException(): FeedbackConsoleHttpException {
    val text = message ?: "Invalid feedback item request"
    val (statusCode, code, action) = when {
        text.startsWith("Cannot save annotation with empty comment") ->
            Triple(HTTP_UNPROCESSABLE_ENTITY, "empty-comment", null)
        text.startsWith("Selected node does not exist on preview:") ->
            Triple(HTTP_BAD_REQUEST, "selected_node_missing", "recapture_or_convert_to_area")
        text.startsWith("Selection bounds") ->
            Triple(HTTP_BAD_REQUEST, "invalid_selection_bounds", "recapture_or_select_area")
        else ->
            Triple(HTTP_BAD_REQUEST, "invalid_feedback_item", null)
    }
    return FeedbackConsoleHttpException(
        statusCode = statusCode,
        message = text,
        errorCode = code,
        action = action,
        cause = this,
    )
}

private const val HTTP_STATUS_CONFLICT = 409
private const val HTTP_STATUS_PRECONDITION_FAILED = 412
private const val ITEM_ID_CAPTURE_GROUP = 1
private val runtimeEvidenceCollectPath = Regex("^/api/items/([^/]+)/runtime-evidence/collect$")
