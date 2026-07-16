package com.soma.wes.auth.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

/**
 * 인증 도메인의 실패 원인. 소셜 로그인과 토큰은 "로그인한다"는 하나의 흐름이라 코드도 한곳에 모은다.
 *
 * 401 구간은 클라이언트가 "재발급을 시도할지(만료)" 대 "다시 로그인시킬지(위조·손상·불일치)"를
 * 가르는 데 쓰이므로, 원인을 뭉뚱그리지 않고 나눠 둔다.
 */
enum class AuthErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    // 400 — 클라이언트가 보낸 값이 잘못된 경우
    PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "AUTH_400_1", "지원하지 않는 소셜 로그인입니다."),
    AUTH_CODE_INVALID(HttpStatus.BAD_REQUEST, "AUTH_400_2", "유효하지 않은 인가 코드입니다."),

    // 401 — 우리가 발급한 토큰의 검증 실패
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_401_1", "만료된 토큰입니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_401_2", "유효하지 않은 토큰입니다."),
    TOKEN_NOT_SIGNED(HttpStatus.UNAUTHORIZED, "AUTH_401_3", "서명이 올바르지 않은 토큰입니다."),
    TOKEN_EMPTY(HttpStatus.UNAUTHORIZED, "AUTH_401_4", "토큰이 비어 있습니다."),
    TOKEN_TYPE_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_401_5", "토큰의 종류가 올바르지 않습니다."),

    // 재발급 실패. 서명이 맞더라도 저장소의 토큰과 다르면(로그아웃·재로그인 이후 등) 여기에 걸린다.
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_401_6", "다시 로그인해 주세요."),

    // 토큰 자체는 유효하지만 그 주인이 사라진 경우(탈퇴 등). 조회 대상이 없는
    // [com.soma.wes.user.exception.UserErrorCode.USER_NOT_FOUND](404)와 달리 인증 실패다.
    TOKEN_OWNER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH_401_7", "토큰의 사용자를 찾을 수 없습니다."),

    // 401 — provider가 발급한 토큰이 거부된 경우
    OAUTH_ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_401_8", "소셜 로그인 토큰이 유효하지 않습니다."),

    // 클라이언트 잘못이 아니라 우리 설정이 잘못된 것이다. 배포 설정을 확인해야 한다.
    OAUTH_MISCONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_500_1", "소셜 로그인 설정이 올바르지 않습니다."),

    OAUTH_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "AUTH_502_1", "소셜 로그인 서버와 통신하지 못했습니다."),
    OAUTH_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AUTH_502_2", "소셜 로그인 서버의 응답을 해석하지 못했습니다."),
}
