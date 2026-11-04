package com.soma.wes.user.dto.response

import com.soma.wes.user.domain.UserWorkspaceKind
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

/** 홈 화면에서 실제로 이동할 수 있는 소속만 반환한다. 빈 PERSONAL 작업공간은 노출하지 않는다. */
data class UserWorkspaceResponse(
    val id: Long,
    val kind: UserWorkspaceKind,
    val workspaceId: Long,
    @field:Schema(description = "kind가 GALLERY인 행의 갤러리 id. STUDIO 행이면 null이다.", example = "128")
    val galleryId: Long?,
    val name: String,
    val role: WorkspaceRole,
    @field:Schema(
        description = "생성·수정 시각과 활동 기록 중 가장 늦은 시각. 목록은 이 값의 최신순이다.",
        example = "2026-11-03T18:20:00+09:00",
    )
    val lastActivityAt: ZonedDateTime?,
    @field:Schema(
        description = "행이 속한 작업공간의 종류. GALLERY 행이면 개인 작업공간 갤러리는 PERSONAL, 스튜디오 갤러리는 STUDIO다.",
        example = "PERSONAL",
    )
    val workspaceType: WorkspaceType? = null,
)
