package com.soma.wes.admin.resource.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.PositiveOrZero

data class CreateAdminResourceRequest(
    @field:Schema(
        description = "만들 리소스의 필드 값(키는 리소스 정의의 필드 이름). 정의에 없거나 생성에서 받지 않는 키, 생성 필수 필드 누락, 생성을 지원하지 않는 종류는 400(ADMIN_400_6)이다.",
    )
    val fields: Map<String, Any?>,
)

data class UpdateAdminResourceRequest(
    @field:PositiveOrZero
    @field:Schema(
        description = "조회한 리소스의 version. 다르면 409(ADMIN_409_5)이다.",
        example = "3",
    )
    val expectedVersion: Long,
    @field:Schema(
        description = "바꿀 필드만 담는다. 정의에 없거나 수정이 막힌 키, 빈 맵, 수정을 지원하지 않는 종류는 400(ADMIN_400_6)이다.",
    )
    val fields: Map<String, Any?>,
)

data class ChangeAdminResourceStateRequest(
    @field:PositiveOrZero
    @field:Schema(
        description = "삭제·복원·정지·활성화 직전에 조회한 리소스의 version. 다르면 409(ADMIN_409_5)이다.",
        example = "3",
    )
    val expectedVersion: Long,
)
