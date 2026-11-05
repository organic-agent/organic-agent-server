package com.soma.wes.auth.service.port

import com.soma.wes.auth.dto.OAuthRegistrationDto

/**
 * 소셜 로그인 서버와의 통신. 토큰 요청 폼·응답 필드 같은 프로토콜 모양은 구현이 안다.
 */
interface OAuthClient {

    /** 인가 코드를 소셜 서버의 access token으로 바꾼다. */
    fun exchangeCode(registration: OAuthRegistrationDto, code: String): String

    /** access token으로 소셜 서버의 사용자 정보 응답을 받는다. 응답 해석은 provider별 추출기가 맡는다. */
    fun fetchUserAttributes(registration: OAuthRegistrationDto, accessToken: String): Map<String, Any>
}
