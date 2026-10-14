package com.soma.wes.workspace.dto

import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceType

/** 권한 판단에 쓰는 작업공간의 두 값. 둘 다 만든 뒤 바뀌지 않아(`updatable = false`) 캐시에 담아도 된다(#286 (k)). */
data class WorkspaceAccessDto(
    val type: WorkspaceType,
    val personalOwnerUserId: Long?,
) {
    companion object {
        fun from(workspace: Workspace) = WorkspaceAccessDto(
            type = workspace.type,
            personalOwnerUserId = workspace.personalOwnerUserId,
        )
    }
}
