package com.soma.wes.workspace.dto.response

import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType

data class WorkspaceResponse(
    val id: Long,
    val type: WorkspaceType,
    val name: String,
    val role: WorkspaceRole,
) {
    companion object {
        fun of(workspace: Workspace, role: WorkspaceRole) = WorkspaceResponse(
            id = workspace.requiredId,
            type = workspace.type,
            name = workspace.name,
            role = role,
        )
    }
}
