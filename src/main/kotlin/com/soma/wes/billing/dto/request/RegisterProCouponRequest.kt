package com.soma.wes.billing.dto.request

import com.soma.wes.billing.domain.ProCoupon
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterProCouponRequest(
    @field:NotBlank
    @field:Size(max = ProCoupon.MAX_CODE_LENGTH)
    @field:Schema(description = "발급받은 일회용 코드. 등록은 쿠폰을 보관하며 이용 기간을 시작하지 않는다.", example = "WES-0123456789ABCDEF0123456789ABCDEF")
    val code: String,
)
