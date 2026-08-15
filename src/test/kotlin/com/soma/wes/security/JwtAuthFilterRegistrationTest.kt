package com.soma.wes.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.security.filter.JwtAuthFilter
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import jakarta.servlet.ServletContext
import org.assertj.core.api.Assertions.assertThat
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
 * `JwtAuthFilter`가 시큐리티 체인에만 등록되고, 서블릿 컨테이너에는 등록되지 않는지 확인한다.
 *
 * 필터에 `@Component`를 붙이면 Spring Boot가 서블릿 필터로도 자동 등록해 한 요청에서 두 번 호출된다.
 * 지금은 `OncePerRequestFilter`의 가드가 두 번째 호출을 막아 실행은 한 번뿐이지만,
 * 시큐리티 체인에서 제외한 경로가 생기면 그 가드가 걸리지 않아 필터가 되살아난다.
 * 자세한 내용은 docs/jwt-filter-double-registration.md 참고.
 *
 * MockMvc가 아니라 실제 톰캣(RANDOM_PORT)이어야 서블릿 컨테이너의 필터 등록이 그대로 재현된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class JwtAuthFilterRegistrationTest @Autowired constructor(
    private val servletContext: ServletContext,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `JwtAuthFilter는 서블릿 컨테이너에 등록되지 않는다`() {
        // when
        val registeredFilters = servletContext.filterRegistrations.values.map { it.className }

        // then
        // 시큐리티 체인 진입점(DelegatingFilterProxy)만 서블릿 필터여야 한다.
        assertThat(registeredFilters)
            .withFailMessage("JwtAuthFilter가 서블릿 필터로 등록됐다. @Component가 다시 붙었는지 확인할 것. 등록된 필터: $registeredFilters")
            .doesNotContain(JwtAuthFilter::class.java.name)
    }

    @Test
    fun `인증된 요청에서 JwtAuthFilter는 한 번만 실행된다`() {
        // given
        val user = userRepository.save(
            User(provider = OAuthProvider.KAKAO, providerId = "filter-probe", nickname = "테스터"),
        )
        val accessToken = authTokenProvider.generateAccessToken(user)

        val filterLogger = LoggerFactory.getLogger(JwtAuthFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        filterLogger.level = Level.DEBUG
        filterLogger.addAppender(appender)

        // when
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/users/me"))
            .header("Authorization", "Bearer ${accessToken.value}")
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        filterLogger.detachAppender(appender)

        // then
        assertThat(response.statusCode()).isEqualTo(200)

        val executions = appender.list.count { it.formattedMessage.startsWith("JwtAuthFilter 실행") }
        assertThat(executions)
            .withFailMessage("필터가 ${executions}번 실행됐다. 이중 등록되면 이 수가 늘어난다.")
            .isEqualTo(1)
    }
}
