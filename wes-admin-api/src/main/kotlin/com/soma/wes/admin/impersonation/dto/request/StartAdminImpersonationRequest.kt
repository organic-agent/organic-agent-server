package com.soma.wes.admin.impersonation.dto.request

import com.soma.wes.admin.resource.domain.AdminResourceType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

data class StartAdminImpersonationRequest(
    @field:Schema(
        description = "대리보기할 대상 종류. USER·STUDIO·GALLERY만 받고 그 밖의 값은 400(ADMIN_400_8)이다.",
        example = "GALLERY",
    )
    val targetType: AdminResourceType,
    @field:Positive
    @field:Schema(
        description = "대상 리소스 id(STUDIO는 workspace id). 삭제·정지된 대상이면 400(ADMIN_400_8)이다.",
        example = "42",
    )
    val targetId: Long,
    @field:Positive
    @field:Schema(
        description = "누구의 권한으로 볼지 정하는 사용자 id. 생략하면 대상 작업공간의 OWNER(USER 대상은 본인)로 보며, USER 대상에 다른 id를 보내거나 대상에 접근 권한이 없는 사용자면 400(ADMIN_400_8)이다.",
        example = "7",
    )
    val viewerUserId: Long? = null,
)
