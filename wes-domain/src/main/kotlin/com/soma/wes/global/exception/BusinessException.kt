package com.soma.wes.global.exception

/**
 * 비즈니스 규칙 위반. [GlobalExceptionHandler]가 [errorCode]를 그대로 응답으로 옮긴다.
 */
open class BusinessException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
