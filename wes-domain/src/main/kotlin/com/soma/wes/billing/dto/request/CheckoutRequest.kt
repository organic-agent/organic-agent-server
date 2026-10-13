package com.soma.wes.billing.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.media.Schema

data class CheckoutRequest(
    @field:NotBlank @field:Size(max = 100)
    @field:Schema(description = "요금제 id. 카드 결제는 준비 중이며 이 API는 이용권 발급 없이 503을 반환한다.")
    val planId: String,
)
