package com.soma.wes.billing.dto.request

import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ResolveProCouponRequest(
    @field:NotBlank
    @field:Size(max = ProCoupon.MAX_CODE_LENGTH)
    @field:Schema(description = "선물 링크의 코드. 조회만 하며 계정에 등록하거나 이용 기간을 시작하지 않는다.", example = "WES-0123456789ABCDEF0123456789ABCDEF")
    val code: String,
)
