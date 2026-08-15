package com.soma.wes.auth.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 이 클래스가 지켜야 하는 성질은 하나다. **같은 출처에서 온 두 요청은 같은 redirect-uri를 받는다.**
 * 인가 URL을 만들 때와 인가 코드를 교환할 때의 값이 어긋나면 provider가 redirect_uri_mismatch로
 * 거절하는데, 그 에러만으로는 원인을 찾기 어렵다.
 */
class OAuthRedirectUriResolverTest {

    companion object {
        private const val CONFIGURED = "https://easyselect.vercel.app/login/oauth2/code/google"
        private const val LOCAL = "http://localhost:3000"
        private const val DEPLOYED = "https://easyselect.vercel.app"
    }

    private fun resolver(fallback: String = LOCAL) = OAuthRedirectUriResolver(
        allowedOrigins = listOf(DEPLOYED, LOCAL),
        fallbackOrigin = fallback,
    )

    @Nested
    @DisplayName("허용된 Origin으로 온 요청")
    inner class AllowedOrigin {

        @Test
        fun `로컬 프론트에서 오면 로컬로 돌려보낸다`() {
            // when
            val resolved = resolver().resolve(CONFIGURED, LOCAL)

            // then
            // 오리진만 바뀌고 경로는 설정값 그대로여야 한다. provider마다 콜백 경로가 다를 수 있어
            // 경로를 코드가 조립하기 시작하면 설정이 무력해진다.
            assertThat(resolved).isEqualTo("http://localhost:3000/login/oauth2/code/google")
        }

        @Test
        fun `배포 프론트에서 오면 설정값 그대로다`() {
            // when & then
            assertThat(resolver().resolve(CONFIGURED, DEPLOYED)).isEqualTo(CONFIGURED)
        }

        @Test
        fun `기본 포트를 쓰는 오리진에 포트를 덧붙이지 않는다`() {
            // URI.getPort()가 -1을 주는데 이걸 그대로 문자열에 넣으면 `https://host:-1/...`이 된다.
            // when
            val resolved = resolver().resolve("http://localhost:3000/login/oauth2/code/kakao", DEPLOYED)

            // then
            assertThat(resolved).isEqualTo("https://easyselect.vercel.app/login/oauth2/code/kakao")
        }
    }

    @Nested
    @DisplayName("프론트가 아닌 곳에서 온 요청")
    inner class NonFrontendOrigin {

        /**
         * Swagger에서 로그인을 시도하면 이 두 경우가 **한 흐름 안에서 같이** 나온다.
         * 브라우저가 same-origin GET에는 Origin을 안 붙이고 POST에는 붙이기 때문이다.
         * 둘이 같은 값으로 떨어지지 않으면 Swagger로는 로그인을 끝낼 수 없다.
         */
        @Test
        fun `Origin이 없을 때와 우리 자신일 때가 같은 값을 준다`() {
            // given
            val resolver = resolver()

            // when
            val fromGet = resolver.resolve(CONFIGURED, null)
            val fromPost = resolver.resolve(CONFIGURED, "https://api.easyselect.kr")

            // then
            assertThat(fromPost).isEqualTo(fromGet)
            assertThat(fromGet).isEqualTo("http://localhost:3000/login/oauth2/code/google")
        }
    }

    @Nested
    @DisplayName("fallback을 설정하지 않았을 때")
    inner class NoFallback {

        @Test
        fun `설정된 redirect-uri를 그대로 쓴다`() {
            // 런칭 후 fallback 키를 지우면 이 상태가 된다. 테스트 편의 장치만 사라지고
            // 정상 동작은 그대로여야 한다.
            // given
            val resolver = resolver(fallback = "")

            // when & then
            assertThat(resolver.resolve(CONFIGURED, null)).isEqualTo(CONFIGURED)
            assertThat(resolver.resolve(CONFIGURED, "https://attacker.example.com")).isEqualTo(CONFIGURED)
        }

        @Test
        fun `허용된 Origin은 여전히 반영한다`() {
            // when & then
            assertThat(resolver(fallback = "").resolve(CONFIGURED, LOCAL))
                .isEqualTo("http://localhost:3000/login/oauth2/code/google")
        }
    }
}
