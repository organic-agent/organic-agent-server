package com.soma.wes.security.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.security.core.AuthenticationException

/**
 * 인증에 실패한 상황. [CustomAuthenticationEntryPoint]가 이 예외를 받아 [errorCode]대로 401을 응답한다.
 *
 * 실패 원인을 [ErrorCode]로만 받으므로, 어느 도메인이든 자기 에러 코드를 들고 이 예외를 상속할 수 있다.
 * 토큰 검증 실패는 [com.soma.wes.auth.exception.TokenException]이 그렇게 쓴다.
 */
open class CustomAuthenticationException(
    val errorCode: ErrorCode,
) : AuthenticationException(errorCode.message)
