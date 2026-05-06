package io.github.pointpatch.compose.core.domain.common

@JvmInline
value class AnnotationId(val value: String) {
    init {
        require(value.isNotBlank()) { "AnnotationId must not be blank" }
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

@JvmInline
value class SnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "SnapshotId must not be blank" }
    }
}
