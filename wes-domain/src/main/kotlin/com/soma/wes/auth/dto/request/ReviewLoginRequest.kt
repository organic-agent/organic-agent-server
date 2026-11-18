package com.soma.wes.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class ReviewLoginRequest(
    @field:Schema(description = "심사용 링크(`/review#code=…`)의 code 값", example = "k3Jd9vQ2pLx7Rm4tWb8n")
    val code: String,
)
