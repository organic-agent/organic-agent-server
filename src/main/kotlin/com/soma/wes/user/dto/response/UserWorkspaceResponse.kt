package com.soma.wes.user.dto.response

import com.soma.wes.workspace.domain.WorkspaceRole
import java.time.ZonedDateTime

enum class UserWorkspaceKind {
    STUDIO,
    GALLERY,
}

/** 홈 화면에서 실제로 이동할 수 있는 소속만 반환한다. 빈 PERSONAL 작업공간은 노출하지 않는다. */
data class UserWorkspaceResponse(
    val id: Long,
    val kind: UserWorkspaceKind,
    val workspaceId: Long,
    val galleryId: Long?,
    val name: String,
    val role: WorkspaceRole,
    val lastActivityAt: ZonedDateTime?,
)
