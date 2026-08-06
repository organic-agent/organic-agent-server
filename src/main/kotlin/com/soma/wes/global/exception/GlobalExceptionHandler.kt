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

    /**
     * 도메인이 어디든 [BusinessException]이면 [ErrorCode]가 상태·코드·메시지를 모두 알고 있으므로,
     * 도메인별 핸들러를 따로 둘 필요가 없다.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> {
        val errorCode = e.errorCode
        log.warn("요청 처리 실패: {}", errorCode.code)

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(ErrorResponse.from(errorCode))
    }

    /**
     * 필터에서 던져진 인증 예외는 [com.soma.wes.security.exception.CustomAuthenticationEntryPoint]가 처리한다.
     * 이 핸들러는 재발급처럼 컨트롤러 안에서 던져진 경우를 맡아, 같은 형태의 응답을 낸다.
     */
    @ExceptionHandler(CustomAuthenticationException::class)
    fun handleAuthenticationException(e: CustomAuthenticationException): ResponseEntity<ErrorResponse> {
        val errorCode = e.errorCode
        log.warn("인증 실패: {}", errorCode.code)

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(ErrorResponse.from(errorCode))
    }

    /**
     * 경로 변수나 쿼리 파라미터를 선언한 타입으로 바꾸지 못한 경우. 예: `/api/v1/users/abc`.
     *
     * 실패 원인을 도메인 에러로 돌려주고 싶다면 파라미터를 `String`으로 받아 직접 변환해야 한다.
     * Spring은 변환 실패를 `Enum.valueOf()` 같은 기본 규칙으로 되돌려 보려 하고, 그 과정에서
     * 변환기가 던진 예외를 삼킨다. [com.soma.wes.auth.controller.OAuthController]가 그렇게 한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.warn("요청 값 변환 실패: {}={}", e.name, e.value)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_PARAMETER.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_PARAMETER))
    }

    /**
     * 요청 본문을 DTO로 읽지 못한 경우. 필수 필드 누락이나 깨진 JSON이 여기에 걸린다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("요청 본문을 읽지 못했습니다: {}", e.message)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_REQUEST_BODY.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_REQUEST_BODY))
    }

    /**
     * 본문은 읽혔지만 제약(`@NotBlank`, `@Size` 등)을 어긴 경우.
     *
     * 이걸 잡지 않으면 아래 [handleUnexpected]가 삼켜 500이 된다. 클라이언트가 고칠 수 있는
     * 잘못을 서버 장애로 알려주는 셈이라, "다시 시도"가 아니라 "값을 고쳐라"를 말해줘야 한다.
     *
     * 어느 필드가 왜 틀렸는지는 응답에 담지 않는다. [ErrorResponse]는 `{code, message}` 한
     * 형태로 고정되어 있고, 필드 목록을 여기에만 얹으면 그 계약이 이 경로에서만 깨진다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailure(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}=${it.defaultMessage}" }
        log.warn("요청 본문 검증 실패: {}", fields)

        return ResponseEntity
            .status(GlobalErrorCode.INVALID_REQUEST_BODY.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INVALID_REQUEST_BODY))
    }

    /**
     * 매핑된 핸들러가 없는 경로. 아래 [handleUnexpected]가 먼저 삼키면 오타 난 URL까지 500이 되므로
     * 반드시 따로 잡아준다.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        log.warn("존재하지 않는 경로: {}", e.resourcePath)

        return ResponseEntity
            .status(GlobalErrorCode.NOT_FOUND.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.NOT_FOUND))
    }

    /**
     * 어디서도 잡지 못한 예외. 원인을 클라이언트에 노출하지 않되, 로그에는 스택 트레이스를 남긴다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("예기치 못한 오류", e)

        return ResponseEntity
            .status(GlobalErrorCode.INTERNAL_SERVER_ERROR.httpStatus)
            .body(ErrorResponse.from(GlobalErrorCode.INTERNAL_SERVER_ERROR))
    }
}
