package com.soma.wes.security.exception

import com.soma.wes.global.exception.ErrorResponse
import com.soma.wes.global.exception.BusinessException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 인증은 됐지만 권한이 부족한 요청에 대해 403을 응답한다.
 */
@Component
class CustomAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val errorCode = generateSequence<Throwable>(accessDeniedException) { it.cause }
            .filterIsInstance<BusinessException>()
            .firstOrNull()
            ?.errorCode
            ?: AuthorizationErrorCode.ACCESS_DENIED

        response.status = errorCode.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.from(errorCode))
    }
}
