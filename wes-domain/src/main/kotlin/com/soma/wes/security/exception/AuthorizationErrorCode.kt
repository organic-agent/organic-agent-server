package com.soma.wes.security.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class AuthorizationErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTHZ_401_1", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTHZ_403_1", "접근 권한이 없습니다."),
}
