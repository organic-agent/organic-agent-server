package com.soma.wes.global.exception

import com.soma.wes.security.exception.CustomAuthenticationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> {
        val errorCode = e.errorCode
        log.warn("요청 처리 실패: {}", errorCode.code)

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(ErrorResponse.from(errorCode))
    }

    @ExceptionHandler(CustomAuthenticationException::class)
    fun handleAuthenticationException(e: CustomAuthenticationException): ResponseEntity<ErrorResponse> {
        val errorCode = e.errorCode
        log.warn("인증 실패: {}", errorCode.code)

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(ErrorResponse.from(errorCode))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.warn("요청 값 변환 실패: {}={}", e.name, e.value)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_PARAMETER.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_PARAMETER))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("요청 본문을 읽지 못했습니다: {}", e.message)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_REQUEST_BODY.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_REQUEST_BODY))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailure(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}=${it.defaultMessage}" }
        log.warn("요청 본문 검증 실패: {}", fields)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_REQUEST_BODY.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_REQUEST_BODY))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        log.warn("존재하지 않는 경로: {}", e.resourcePath)

        return ResponseEntity
            .status(GlobalErrorCode.NOT_FOUND.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.NOT_FOUND))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("예기치 못한 오류", e)

        return ResponseEntity
            .status(GlobalErrorCode.INTERNAL_SERVER_ERROR.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INTERNAL_SERVER_ERROR))
    }
}
