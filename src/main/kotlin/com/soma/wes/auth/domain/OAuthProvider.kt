package com.soma.wes.auth.domain

import com.fasterxml.jackson.annotation.JsonValue
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException

enum class OAuthProvider {
    GOOGLE,
    NAVER,
    KAKAO,
    ;

    /**
     * 이 enum의 대외 표기. 우리가 내보내는 값(JSON 응답, OpenAPI 문서, 로그)은 모두 이 표기 하나다.
     * 받을 때는 [from]이 대소문자를 관용하지만, 그건 편의일 뿐 계약이 아니다.
     *
     * OAuth 설정(`spring.security.oauth2.client.registration.*`)의 registration id가 소문자라
     * 그쪽에 맞췄다. [JsonValue]를 붙여 두면 Jackson과 springdoc이 enum 이름(`NAVER`)이 아니라
     * 이 값(`naver`)을 쓰므로, 문서에 적힌 값과 서버가 받는 값이 갈릴 수 없다.
     *
     * DB에는 `@Enumerated(EnumType.STRING)`으로 enum 이름이 그대로 저장된다(표기와 무관).
     */
    @get:JsonValue
    val key: String
        get() = name.lowercase()

    companion object {

        /**
         * 대소문자를 가리지 않고 provider를 찾는다. `naver`도 `NAVER`도 같은 값으로 읽는다.
         *
         * 문서가 광고하는 표기는 [key] 하나뿐이지만, 받아줄 때는 관대해도 손해가 없다.
         * 어느 표기로 들어오든 여기서 enum으로 정규화되므로, 클라이언트가 쓴 철자는 이 경계를 넘지 못한다.
         * 로그·응답·DB에는 정규화된 값만 남는다.
         *
         * 지원하지 않는 값이면 [OAuthException]을 던진다. `IllegalArgumentException`으로 실패하면
         * "요청 값이 올바르지 않습니다"로 뭉개져, 클라이언트가 provider가 문제라는 걸 알 수 없다.
         */
        fun from(value: String): OAuthProvider =
            entries.find { it.key == value.lowercase() }
                ?: throw OAuthException(AuthErrorCode.PROVIDER_NOT_SUPPORTED)
    }
}
