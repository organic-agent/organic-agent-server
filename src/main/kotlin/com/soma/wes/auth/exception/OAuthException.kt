package com.soma.wes.auth.exception

import com.soma.wes.global.exception.BusinessException

/**
 * 소셜 로그인 실패. 컨트롤러까지 올라와 `GlobalExceptionHandler`가 응답으로 옮긴다.
 */
class OAuthException(errorCode: AuthErrorCode) : BusinessException(errorCode)
