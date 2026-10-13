package com.soma.wes.global

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecureTokenGeneratorTest {

    private val generator = SecureTokenGenerator()

    @Test
    fun `부를 때마다 다른 토큰을 만든다`() {
        // when
        val tokens = List(1000) { generator.generate() }

        // then
        assertThat(tokens.toSet().size).isEqualTo(1000)
    }

    @Test
    fun `URL에 그대로 실을 수 있는 문자만 쓴다`() {
        // 링크에 들어가는 값이라 `+`나 `/`가 섞이면 인코딩 여부에 따라 다른 토큰이 된다.
        // given
        val urlSafe = Regex("^[A-Za-z0-9_-]+$")

        // when & then
        repeat(100) {
            val token = generator.generate()
            assertThat(token).describedAs("URL-safe 하지 않은 토큰: $token").matches(urlSafe.pattern)
        }
    }

    @Test
    fun `전수 조사가 불가능한 길이를 갖는다`() {
        // 32바이트를 패딩 없이 base64로 인코딩하면 43자다.
        // when & then
        assertThat(generator.generate().length).isEqualTo(43)
    }
}
