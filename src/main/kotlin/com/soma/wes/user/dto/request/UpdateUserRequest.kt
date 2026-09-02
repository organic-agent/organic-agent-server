package com.soma.wes.user.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateUserRequest(
    @field:NotBlank
    @field:Size(max = 50)
    @field:Schema(description = "서비스에서 사용할 닉네임", example = "웨스")
    val nickname: String,
)
