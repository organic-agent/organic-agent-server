package com.soma.wes.admin.resource.dto.request

import com.soma.wes.admin.resource.domain.AdminReprocessScope
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class AdminReprocessRequest(
    @field:PositiveOrZero
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 128)
    val idempotencyKey: String,
    /** 생략하면 [AdminReprocessScope.ALL] — 이 필드가 생기기 전의 요청과 같은 동작이다. */
    val scope: AdminReprocessScope = AdminReprocessScope.ALL,
)
