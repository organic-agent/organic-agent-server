package com.soma.wes.admin.dto.request

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAuthEvent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateAdminAccountRequest(
    @field:NotBlank
    @field:Size(min = AdminAccount.USERNAME_MIN_LENGTH, max = AdminAccount.USERNAME_MAX_LENGTH)
    @field:Schema(description = "영문 소문자·숫자·점·밑줄·하이픈으로 구성한 관리자 아이디")
    val username: String,

    @field:NotBlank
    @field:Size(max = AdminAccount.DISPLAY_NAME_MAX_LENGTH)
    @field:Schema(description = "감사 이력과 화면에 표시할 관리자 이름")
    val displayName: String,

    @field:NotBlank
    @field:Size(max = AdminAuthEvent.REASON_MAX_LENGTH)
    @field:Schema(description = "계정 생성 사유")
    val reason: String,
)
