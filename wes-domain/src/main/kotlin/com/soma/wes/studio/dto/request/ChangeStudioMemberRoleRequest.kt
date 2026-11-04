package com.soma.wes.studio.dto.request

import com.soma.wes.workspace.domain.WorkspaceRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

data class ChangeStudioMemberRoleRequest(
    @field:NotNull
    @field:Schema(
        description = "바꿀 역할. 스튜디오 소유자(OWNER)만 바꿀 수 있고, 마지막 남은 OWNER를 MEMBER로 내리는 것은 거절한다.",
        example = "MEMBER",
    )
    val role: WorkspaceRole,
)
