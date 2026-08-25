package com.soma.wes.admin.controller

import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.security.filter.AdminMutationHeaderFilter
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminAuthControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
) {

    @Test
    fun `상태 변경 요청은 백오피스 전용 헤더가 없으면 거부한다`() {
        // given
        adminAccountFixture.관리자("header-owner")

        // when & then
        mockMvc.post("/internal/admin/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = loginBody("header-owner")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `로그인 쿠키는 보안 속성을 가지며 비밀번호 변경 전에는 관리 기능을 막는다`() {
        // given
        adminAccountFixture.관리자("web-owner")

        // when
        val loginResponse = mockMvc.post("/internal/admin/v1/auth/login") {
            adminMutationHeader()
            contentType = MediaType.APPLICATION_JSON
            content = loginBody("web-owner")
        }.andExpect {
            status { isOk() }
            jsonPath("$.admin.mustChangePassword") { value(true) }
        }.andReturn().response

        // then: 브라우저 세션 쿠키에는 원문 접근·교차 사이트 전송을 막는 속성이 붙는다.
        val loginSetCookie = requireNotNull(loginResponse.getHeader(HttpHeaders.SET_COOKIE))
        assertThat(loginSetCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/")
        val loginCookie = sessionCookie(loginSetCookie)

        mockMvc.get("/internal/admin/v1/auth/session") {
            cookie(loginCookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.admin.username") { value("web-owner") }
        }

        mockMvc.get("/internal/admin/v1/admins") {
            cookie(loginCookie)
        }.andExpect {
            status { isForbidden() }
        }

        // when: 최초 로그인 비밀번호를 바꾸면 기존 세션 대신 새 세션을 받는다.
        val changedResponse = mockMvc.post("/internal/admin/v1/auth/change-password") {
            adminMutationHeader()
            cookie(loginCookie)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "currentPassword": "${AdminAccountFixture.DEFAULT_PASSWORD}",
                  "newPassword": "changed-admin-password-456"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.admin.mustChangePassword") { value(false) }
        }.andReturn().response

        val changedCookie = sessionCookie(requireNotNull(changedResponse.getHeader(HttpHeaders.SET_COOKIE)))
        assertThat(changedCookie.value).isNotEqualTo(loginCookie.value)

        mockMvc.get("/internal/admin/v1/admins") {
            cookie(changedCookie)
        }.andExpect {
            status { isOk() }
            jsonPath("$.accounts[0].username") { value("web-owner") }
        }
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.adminMutationHeader() {
        header(AdminMutationHeaderFilter.HEADER_NAME, AdminMutationHeaderFilter.HEADER_VALUE)
    }

    private fun loginBody(username: String): String =
        """
            {
              "username": "$username",
              "password": "${AdminAccountFixture.DEFAULT_PASSWORD}"
            }
        """.trimIndent()

    private fun sessionCookie(setCookie: String): Cookie {
        val cookiePair = setCookie.substringBefore(';')
        val name = cookiePair.substringBefore('=')
        val value = cookiePair.substringAfter('=')
        assertThat(name).isEqualTo(AdminSessionCookie.NAME)
        return Cookie(name, value)
    }
}
