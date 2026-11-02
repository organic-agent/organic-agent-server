package com.soma.wes.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min

data class ChangeProCouponStatusRequest(
    @field:Schema(description = "true이면 재활성화, false이면 비활성화. 미사용 쿠폰만 변경한다.")
    val enabled: Boolean,
    @field:Min(0)
    @field:Schema(description = "마지막 조회의 version. 이후 등록·사용·관리 변경이 있으면 409")
    val expectedVersion: Long,
)
