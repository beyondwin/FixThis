package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

internal class FeedbackItemRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean =
        path == "/api/items" ||
            path == "/api/items/batch" ||
            path == "/api/items/draft" ||
            path == "/api/agent-handoffs"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/items" -> exchange.requireMethod("POST") {
                val request = exchange.decodeAddFeedbackItemBody()
                val session = service.currentSession()
                val item = try {
                    runBlocking {
                        service.addFeedbackItem(
                            sessionId = session.sessionId,
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
                exchange.sendJson(200, item)
            }
            "/api/items/batch" -> exchange.requireMethod("POST") {
                val request = exchange.decodeSavePreviewFeedbackItemsBody()
                val session = try {
                    service.savePreviewFeedbackItems(
                        sessionId = service.currentSession().sessionId,
                        previewId = request.previewId,
                        items = request.items,
                        fallbackScreen = request.screen,
                    )
                } catch (error: IllegalArgumentException) {
                    throw FeedbackConsoleHttpException(400, error.message ?: "Invalid feedback item request")
                }
                exchange.sendJson(200, session)
            }
            "/api/items/draft" -> exchange.requireMethod("DELETE") {
                exchange.sendJson(200, service.clearDraftItems(service.currentSession().sessionId))
            }
            "/api/agent-handoffs" -> exchange.requireMethod("POST") {
                val request = exchange.decodeAgentHandoffBody()
                exchange.sendJson(200, service.sendDraftToAgent(service.currentSession().sessionId, request.prompt))
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

    private fun HttpExchange.decodeSavePreviewFeedbackItemsBody(): SaveSnapshotRequest =
        decodeJsonBody(SaveSnapshotRequest.serializer())

    private fun HttpExchange.decodeAgentHandoffBody(): AgentHandoffRequest =
        decodeJsonBody(AgentHandoffRequest.serializer(), blankValue = AgentHandoffRequest())
}

private val allowedAddFeedbackItemRequestKeys = setOf("screenId", "comment", "targetType", "bounds", "nodeUid")
