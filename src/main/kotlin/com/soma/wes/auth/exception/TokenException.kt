package com.soma.wes.auth.exception

import com.soma.wes.security.exception.CustomAuthenticationException

/**
 * 토큰 검증 실패.
 *
 * 다른 도메인 예외와 달리 `BusinessException`이 아니라 [CustomAuthenticationException]을 상속한다.
 * 토큰은 필터에서 검증되는데, 필터는 `DispatcherServlet` 밖이라 `@RestControllerAdvice`가 닿지 않기 때문이다.
 * Spring Security의 `AuthenticationException`이어야 `CustomAuthenticationEntryPoint`가 받아 401을 낼 수 있다.
 */
class TokenException(errorCode: AuthErrorCode) : CustomAuthenticationException(errorCode)
