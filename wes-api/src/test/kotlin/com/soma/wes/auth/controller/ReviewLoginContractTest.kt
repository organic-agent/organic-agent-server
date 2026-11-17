package com.soma.wes.auth.controller

import com.soma.wes.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 심사용 링크 로그인은 토큰 없이 부르는 공개 경로이고, 꺼진 기본 설정에서는 404 에러 응답을 낸다.
 */
@IntegrationTest
class ReviewLoginContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {

    @Test
    fun `토큰 없이 부를 수 있고 기본 설정(꺼짐)에서는 AUTH_404_1이다`() {
        // when & then
        mockMvc.post("/api/v1/auth/review-login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"review-code-0123456789"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("AUTH_404_1") }
        }
    }
}
