package com.soma.wes.global.filter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.global.logging.LogContext
import com.soma.wes.support.TestSequence
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
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
import java.time.Duration

/**
 * 요청 로그의 규약을 지키는 회귀 가드 — traceId가 붙고, 민감한 쿼리 값이 가려지고,
 * 인증된 요청의 RESPONSE 줄에 userId가 MDC 키로 한 번만 실리고, 사전 요청(OPTIONS)은 줄을 남기지 않는지. 실제 톰캣(RANDOM_PORT)이어야 서블릿 필터 순서와
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

        val request = loggedEvents().single { it.formattedMessage.startsWith("[REQUEST]") }
        val result = loggedEvents().single { it.formattedMessage.startsWith("[RESPONSE]") }

        assertThat(request.formattedMessage).isEqualTo("[REQUEST] GET /api/v1/users/me")
        assertThat(result.formattedMessage).isEqualTo("[RESPONSE] /api/v1/users/me (200 OK)")
        assertThat(result.mdcPropertyMap[LogContext.USER_ID]).isEqualTo(user.id.toString())

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
        val request = loggedEvents().single { it.formattedMessage.startsWith("[REQUEST]") }
        assertThat(request.formattedMessage)
            .isEqualTo("[REQUEST] GET /api/v1/users/me?token=****&page=1&AccessToken=****&query=****")
        assertThat(request.formattedMessage).doesNotContain("private@example.com")
    }

    @Test
    fun `인증에서 거절된 요청도 RESPONSE 줄이 남고 사용자 번호는 싣지 않는다`() {
        // when
        val response = send("/api/v1/users/me", accessToken = null)

        // then
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.body()).contains("\"correlationId\":\"")
        val result = loggedEvents().single { it.formattedMessage.startsWith("[RESPONSE]") }
        assertThat(result.formattedMessage).isEqualTo("[RESPONSE] /api/v1/users/me (401 UNAUTHORIZED)")
        assertThat(result.mdcPropertyMap).doesNotContainKey(LogContext.USER_ID)
    }

    @Test
    fun `갤러리 경로와 업로드 세션 헤더와 로그인 사용자는 그 요청의 줄에 실린다`() {
        // given
        val user = userRepository.save(
            User(provider = OAuthProvider.KAKAO, providerId = "logging-probe-${TestSequence.next()}", nickname = "테스터"),
        )
        val accessToken = authTokenProvider.generateAccessToken(user)
        val uploadSession = "3f0c1a52-7c1e-4a55-9f0e-2b6a4a1d9c11"

        // when — 갤러리가 없어 거절되지만, 어느 갤러리·업로드의 요청이었는지는 줄에 남아야 한다
        send("/api/v1/galleries/987654/photos/summary", accessToken.value, uploadSession = uploadSession)

        // then
        val request = loggedEvents().single { it.formattedMessage.startsWith("[REQUEST]") }
        val result = loggedEvents().single { it.formattedMessage.startsWith("[RESPONSE]") }
        assertSoftly { softly ->
            softly.assertThat(request.mdcPropertyMap[LogContext.GALLERY_ID]).isEqualTo("987654")
            softly.assertThat(request.mdcPropertyMap[LogContext.UPLOAD_SESSION_ID]).isEqualTo(uploadSession)
            softly.assertThat(result.mdcPropertyMap[LogContext.GALLERY_ID]).isEqualTo("987654")
            softly.assertThat(result.mdcPropertyMap[LogContext.USER_ID]).isEqualTo(user.id.toString())
        }
    }

    @Test
    fun `형식이 다른 업로드 세션 헤더와 갤러리 밖 경로는 싣지 않는다`() {
        // when — 헤더 값은 그대로 로그에 찍히므로 UUID 꼴이 아니면 버린다
        send("/api/v1/users/me", accessToken = null, uploadSession = "a=b c;d".repeat(20))

        // then
        val request = loggedEvents().single { it.formattedMessage.startsWith("[REQUEST]") }
        assertSoftly { softly ->
            softly.assertThat(request.mdcPropertyMap).doesNotContainKey(LogContext.UPLOAD_SESSION_ID)
            softly.assertThat(request.mdcPropertyMap).doesNotContainKey(LogContext.GALLERY_ID)
            softly.assertThat(request.mdcPropertyMap).doesNotContainKey(LogContext.USER_ID)
        }
    }

    @Test
    fun `헬스체크는 기록하지 않는다`() {
        // when
        val response = send("/actuator/health", accessToken = null)

        // then
        assertThat(appender.list).isEmpty()
        assertThat(response.headers().firstValue(HttpLoggingFilter.CORRELATION_ID_HEADER)).isPresent
    }

    @Test
    fun `CORS 사전 요청은 기록하지 않고 브라우저가 결과를 한 시간 다시 쓰게 한다`() {
        // when
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/users/me"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        // then
        assertSoftly { softly ->
            softly.assertThat(response.statusCode()).isEqualTo(200)
            softly.assertThat(response.headers().firstValue("Access-Control-Max-Age")).hasValue("3600")
            softly.assertThat(response.headers().firstValue(HttpLoggingFilter.CORRELATION_ID_HEADER)).isPresent
            softly.assertThat(appender.list).isEmpty()
        }
    }

    /**
     * 요청 한 건의 [REQUEST]·[RESPONSE] 줄이 다 찍힌 뒤의 사본. 필터는 응답을 내보낸 뒤에 [RESPONSE] 줄을 찍으므로, 응답을 받은
     * 직후에 목록을 그대로 읽으면 서버 스레드가 줄을 더하는 중일 수 있다(ConcurrentModificationException). 줄이 찍히기를 기다렸다가 복사한다.
     */
    private fun loggedEvents(): List<ILoggingEvent> {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            val snapshot = runCatching { appender.list.toList() }.getOrNull()
            if (snapshot != null && snapshot.any { it.formattedMessage.startsWith("[RESPONSE]") }) return snapshot
            Thread.sleep(10)
        }
        return appender.list.toList()
    }

    private fun send(path: String, accessToken: String?, uploadSession: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path")).GET()
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        uploadSession?.let { builder.header(HttpLoggingFilter.UPLOAD_SESSION_HEADER, it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
