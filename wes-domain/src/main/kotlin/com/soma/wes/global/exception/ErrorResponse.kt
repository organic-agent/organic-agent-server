package com.soma.wes.global.exception

import com.soma.wes.global.filter.HttpLoggingFilter
import org.slf4j.MDC

/**
 * 모든 에러 응답의 형태. 필터에서 나가든 컨트롤러에서 나가든 이 형태로 통일한다.
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
) {

    companion object {
        fun from(errorCode: ErrorCode) = ErrorResponse(
            code = errorCode.code,
            message = errorCode.message,
            correlationId = MDC.get(HttpLoggingFilter.TRACE_ID_KEY),
        )
    }
}
