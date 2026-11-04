package com.soma.wes.studio.dto.response

import com.soma.wes.user.domain.User
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import io.swagger.v3.oas.annotations.media.Schema

data class StudioMemberResponse(
    val memberId: Long,
    val userId: Long,
    val nickname: String,
    @field:Schema(description = "OAuth 로그인 제공자(Google·Kakao·Naver)가 준 이메일. 제공자가 이메일을 주지 않은 계정은 null이다.", example = "minji@example.com")
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
