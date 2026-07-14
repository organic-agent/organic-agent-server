package com.soma.wes.global.exception

import org.springframework.http.HttpStatus

/**
 * 특정 도메인에 속하지 않는 요청 처리 실패.
 */
enum class GlobalErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "GLOBAL_400_1", "요청 값이 올바르지 않습니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "GLOBAL_400_2", "요청 본문이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "GLOBAL_404_1", "존재하지 않는 경로입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GLOBAL_500_1", "요청을 처리하지 못했습니다."),
}
