package com.soma.wes.studio.dto.request

import com.soma.wes.workspace.domain.WorkspaceRole
import jakarta.validation.constraints.NotNull

data class ChangeStudioMemberRoleRequest(
    @field:NotNull
    val role: WorkspaceRole,
)
