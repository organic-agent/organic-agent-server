package com.soma.wes.studio.dto.response

import com.soma.wes.user.domain.User
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole

data class StudioMemberResponse(
    val memberId: Long,
    val userId: Long,
    val nickname: String,
    val email: String?,
    val role: WorkspaceRole,
) {
    companion object {
        fun from(member: WorkspaceMember, user: User) = StudioMemberResponse(
            memberId = member.requiredId,
            userId = member.userId,
            nickname = user.nickname,
            email = user.email,
            role = member.role,
        )
    }
}
