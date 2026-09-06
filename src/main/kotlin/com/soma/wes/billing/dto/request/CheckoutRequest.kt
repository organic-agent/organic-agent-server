package com.soma.wes.billing.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.media.Schema

data class CheckoutRequest(
    @field:NotBlank @field:Size(max = 100)
    @field:Schema(description = "GET /plans에서 받은 테스트 플랜 id")
    val planId: String,
)
