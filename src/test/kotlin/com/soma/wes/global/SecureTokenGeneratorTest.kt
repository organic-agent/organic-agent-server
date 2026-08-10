package com.soma.wes.global

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureTokenGeneratorTest {

    private val generator = SecureTokenGenerator()

    @Test
    fun `부를 때마다 다른 토큰을 만든다`() {
        val tokens = List(1000) { generator.generate() }

        assertEquals(1000, tokens.toSet().size)
    }

    @Test
    fun `URL에 그대로 실을 수 있는 문자만 쓴다`() {
        // 링크에 들어가는 값이라 `+`나 `/`가 섞이면 인코딩 여부에 따라 다른 토큰이 된다.
        val urlSafe = Regex("^[A-Za-z0-9_-]+$")

        repeat(100) {
            val token = generator.generate()
            assertTrue(urlSafe.matches(token), "URL-safe 하지 않은 토큰: $token")
        }
    }

    @Test
    fun `전수 조사가 불가능한 길이를 갖는다`() {
        // 32바이트를 패딩 없이 base64로 인코딩하면 43자다.
        assertEquals(43, generator.generate().length)
    }
}
