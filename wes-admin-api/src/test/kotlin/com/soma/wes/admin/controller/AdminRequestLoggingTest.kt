package com.soma.wes.admin.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.fixture.AdminAccountFixture
import com.soma.wes.admin.service.AdminAuthService
import com.soma.wes.admin.support.AdminSessionCookie
import com.soma.wes.global.filter.HttpLoggingFilter
import com.soma.wes.global.logging.LogContext
import com.soma.wes.support.IntegrationTest
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 관리자 API 의 RESPONSE 줄에도 누가 부른 요청인지 남는지 지키는 회귀 가드.
 * 사용자 번호는 메시지가 아니라 MDC 의 `userId` 키로 줄 앞에 붙으므로, 관리자 인증 필터가 그 키를 심어야 한다.
 */
@IntegrationTest
class AdminRequestLoggingTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val adminAccountFixture: AdminAccountFixture,
    private val adminAuthService: AdminAuthService,
) {

    private val filterLogger = LoggerFactory.getLogger(HttpLoggingFilter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        filterLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        filterLogger.detachAppender(appender)
    }

    @Test
    fun `로그인한 관리자의 요청은 RESPONSE 줄에 관리자 번호가 실린다`() {
        // given
        val account = adminAccountFixture.관리자("request-logging")
        val session = adminAuthService.changePassword(
            account.requiredId,
            ChangeAdminPasswordRequest(AdminAccountFixture.DEFAULT_PASSWORD, "request-logging-password-456"),
            "127.0.0.1",
        )

        // when
        mockMvc.get("/internal/admin/v1/resources") {
            cookie(Cookie(AdminSessionCookie.NAME, session.rawSessionToken))
        }.andExpect { status { isOk() } }

        // then
        val result = appender.list.single { it.formattedMessage.startsWith("[RESPONSE]") }
        assertThat(result.formattedMessage).doesNotContain("userId=")
        assertThat(result.mdcPropertyMap[LogContext.USER_ID]).isEqualTo(account.requiredId.toString())
    }
}
