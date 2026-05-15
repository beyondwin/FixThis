package io.github.beyondwin.fixthis.compose.core.domain.annotation

interface AnnotationRepository {
    suspend fun save(annotation: Annotation): Annotation
}
