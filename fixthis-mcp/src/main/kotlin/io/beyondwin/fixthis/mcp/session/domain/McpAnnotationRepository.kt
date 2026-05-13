package io.beyondwin.fixthis.mcp.session.domain

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.toAnnotationDto
import io.beyondwin.fixthis.mcp.session.toDomainAnnotation

class McpAnnotationRepository(
    private val store: FeedbackSessionStore,
) : AnnotationRepository {
    override suspend fun save(annotation: Annotation): Annotation {
        val dto = annotation.toAnnotationDto()
        store.addOrReplaceAnnotationForDomain(annotation.sessionId.value, dto)
        return store
            .getSession(annotation.sessionId.value)
            .items
            .first { it.itemId == annotation.id.value }
            .toDomainAnnotation(annotation.sessionId.value)
    }
}
