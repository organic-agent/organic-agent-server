package com.soma.wes.global.filter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.support.TestSequence
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 요청 로그의 규약을 지키는 회귀 가드 — traceId가 붙고, 민감한 쿼리 값이 가려지고,
 * 인증된 요청의 RESPONSE 줄에 userId가 찍히는지. 실제 톰캣(RANDOM_PORT)이어야 서블릿 필터 순서와
 * 시큐리티 체인이 그대로 재현된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class HttpLoggingFilterTest @Autowired constructor(
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    @LocalServerPort
    private var port: Int = 0

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
    fun `인증된 요청은 traceId와 userId가 함께 기록된다`() {
        // given
        val user = userRepository.save(
            User(provider = OAuthProvider.KAKAO, providerId = "logging-probe-${TestSequence.next()}", nickname = "테스터"),
        )
        val accessToken = authTokenProvider.generateAccessToken(user)

        // when
        val response = send("/api/v1/users/me", accessToken.value)

        // then
        assertThat(response.statusCode()).isEqualTo(200)

        val request = appender.list.single { it.formattedMessage.startsWith("[REQUEST]") }
        val result = appender.list.single { it.formattedMessage.startsWith("[RESPONSE]") }

        assertThat(request.formattedMessage).isEqualTo("[REQUEST] GET /api/v1/users/me")
        assertThat(result.formattedMessage).isEqualTo("[RESPONSE] /api/v1/users/me userId=${user.id} (200 OK)")

        val traceId = request.mdcPropertyMap[HttpLoggingFilter.TRACE_ID_KEY]
        assertThat(traceId).hasSize(16)
        assertThat(result.mdcPropertyMap[HttpLoggingFilter.TRACE_ID_KEY]).isEqualTo(traceId)
        assertThat(response.headers().firstValue(HttpLoggingFilter.CORRELATION_ID_HEADER)).hasValue(traceId)
    }

    @Test
    fun `민감한 쿼리 파라미터 값은 가려지고 나머지는 그대로 남는다`() {
        // when
        send(
            "/api/v1/users/me?token=secret&page=1&AccessToken=abc&query=private%40example.com",
            accessToken = null,
        )

        // then
        val request = appender.list.single { it.formattedMessage.startsWith("[REQUEST]") }
        assertThat(request.formattedMessage)
            .isEqualTo("[REQUEST] GET /api/v1/users/me?token=****&page=1&AccessToken=****&query=****")
        assertThat(request.formattedMessage).doesNotContain("private@example.com")
    }

    @Test
    fun `인증에서 거절된 요청도 RESPONSE 줄이 남는다`() {
        // when
        val response = send("/api/v1/users/me", accessToken = null)

        // then
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("\"correlationId\":\"")
        assertThat(appender.list.map { it.formattedMessage })
            .contains("[RESPONSE] /api/v1/users/me userId=null (401 UNAUTHORIZED)")
    }

    @Test
    fun `헬스체크는 기록하지 않는다`() {
        // when
        val response = send("/actuator/health", accessToken = null)

        // then
        assertThat(appender.list).isEmpty()
        assertThat(response.headers().firstValue(HttpLoggingFilter.CORRELATION_ID_HEADER)).isPresent
    }

    private fun send(path: String, accessToken: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).GET()
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
