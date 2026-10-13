package com.soma.wes.admin.resource.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class AdminWorkflowRequest(
    val action: AdminWorkflowAction,
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String,
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val idempotencyKey: String,
    val confirm: Boolean,
    @field:Schema(
        description = "액션별 입력. REISSUE_GALLERY_INVITE는 kind(STUDIO_MEMBER/GALLERY_MEMBER/PERSONAL_PARTNER), maxUses(1~100), 미래 expiresAt을 선택적으로 받으며 생략한 kind/maxUses는 직전 초대 정책을 유지한다.",
    )
    val fields: Map<String, Any?> = emptyMap(),
)
