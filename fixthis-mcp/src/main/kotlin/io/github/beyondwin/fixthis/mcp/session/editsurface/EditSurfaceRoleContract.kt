package io.github.beyondwin.fixthis.mcp.session.editsurface

import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto

internal data class EditSurfaceRoleContract(
    val role: EditSurfaceRoleDto,
    val actionGuidance: String,
)

internal object EditSurfaceRoleContracts {
    fun forRole(role: EditSurfaceRoleDto): EditSurfaceRoleContract = when (role) {
        EditSurfaceRoleDto.CALL_SITE -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "edit the matched call site, then verify the preview",
        )
        EditSurfaceRoleDto.COMPONENT_DEFINITION -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "verify call-site impact and each call site before editing the shared component definition",
        )
        EditSurfaceRoleDto.COPY_OR_DATA -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "confirm whether the change belongs in copy/data or the renderer",
        )
        EditSurfaceRoleDto.LAYOUT_OR_STYLE -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "treat layout renderer context as an edit hint and verify before editing",
        )
        EditSurfaceRoleDto.VISUAL_AREA -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "treat source paths as hints because the target is a visual area",
        )
        EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "verify runtime target and boundary context before editing",
        )
    }
}
