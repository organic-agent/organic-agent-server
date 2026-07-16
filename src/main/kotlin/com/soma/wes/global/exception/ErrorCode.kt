package com.soma.wes.global.exception

import org.springframework.http.HttpStatus

/**
 * 에러 응답의 계약.
 *
 * 코드 목록은 도메인별 enum이 각자 갖는다(예: `AuthErrorCode`, `UserErrorCode`).
 * 한 파일에 모든 도메인의 에러를 모으면 기능마다 그 파일이 바뀌어 병합 충돌이 잦고,
 * 코드가 중복되거나 규칙이 어긋나도 목록이 길어 아무도 알아채지 못한다.
 *
 * enum은 도메인 폴더의 `exception` 패키지 한 곳에만 둔다(예: `auth/exception/AuthErrorCode`).
 */
interface ErrorCode {

    /** 응답 상태. */
    val httpStatus: HttpStatus

    /**
     * 클라이언트가 분기에 쓰는 식별자. `{도메인}_{HTTP상태}_{일련번호}` 형식(예: `AUTH_401_2`).
     *
     * 상태와 일련번호를 `_`로 끊는 이유는, 붙여 쓰면 `AUTH_4041`이 "404의 1번"인지
     * "400의 41번"인지 읽는 사람이 알 수 없어 규칙이 어긋나도 눈에 띄지 않기 때문이다.
     * 실제로 404 코드가 `GLOBAL_4004`로 들어와 있었고 아무도 알아채지 못했다.
     * [com.soma.wes.global.exception.ErrorCodeFormatTest]가 이 형식과 [httpStatus]의 일치를 강제한다.
     */
    val code: String

    /** 사용자에게 보여줄 메시지. */
    val message: String
}
