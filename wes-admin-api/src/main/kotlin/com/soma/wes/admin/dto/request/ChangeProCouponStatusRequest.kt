package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAuthEvent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChangeProCouponStatusRequest(
    @field:Schema(description = "true이면 재활성화, false이면 비활성화. 미사용 쿠폰만 변경한다.")
    val enabled: Boolean,
    @field:Min(0)
    @field:Schema(description = "마지막 조회의 version. 이후 등록·사용·관리 변경이 있으면 409")
    val expectedVersion: Long,
    @field:NotBlank @field:Size(max = AdminAuthEvent.REASON_MAX_LENGTH)
    @field:Schema(description = "상태 변경 사유. 감사 로그에는 허용된 분류만 보존한다.")
    val reason: String,
)
