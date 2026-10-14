package com.soma.wes.security.exception

import com.soma.wes.global.exception.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증되지 않은 요청(토큰 없음·만료·위조)에 대해 401을 응답한다.
 * 기본 동작은 로그인 폼으로 리다이렉트하는 것이라, API 서버에서는 반드시 대체해야 한다.
 */
@Component
class CustomAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // 필터는 SecurityContext 밖이라 @RestControllerAdvice가 닿지 않는다. 여기서 직접 응답을 만든다.
        //
        // 토큰을 검증하다 실패한 경우에만 그 원인(만료·위조 등)을 그대로 내보낸다.
        // 그 외(= 애초에 자격 증명이 없는 요청)는 토큰 문제로 단정할 수 없으므로 "인증 필요"로 응답한다.
        val errorCode = (authException as? CustomAuthenticationException)?.errorCode
            ?: AuthorizationErrorCode.AUTHENTICATION_REQUIRED

        response.status = errorCode.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.from(errorCode))
    }
}
