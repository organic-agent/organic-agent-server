package com.soma.wes.global.exception

/**
 * 모든 에러 응답의 형태. 필터에서 나가든 컨트롤러에서 나가든 이 형태로 통일한다.
 */
data class ErrorResponse(
    val code: String,
    val message: String,
) {

    companion object {
        fun from(errorCode: ErrorCode) = ErrorResponse(
            code = errorCode.code,
            message = errorCode.message,
        )
    }
}
