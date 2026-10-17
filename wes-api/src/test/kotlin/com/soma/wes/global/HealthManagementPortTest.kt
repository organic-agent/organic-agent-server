package com.soma.wes.global

import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 서버 compose 는 헬스를 관리 포트(MANAGEMENT_SERVER_PORT=8090)로 뺀다(#313 W5b) — 사용자 요청이 본 포트 Tomcat 스레드를 다 쥐어도
 * ALB liveness 가 줄 서지 않게. 관리 포트에도 시큐리티 체인이 걸리므로 토큰 없이 열리는지, 본 포트의 `/livez`·`/readyz` 가
 * 남는지(관리 포트를 열지 않은 운영 P1 전 길)를 실제 Tomcat 으로 본다. MockMvc 로는 관리 포트가 뜨지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@Import(TestcontainersConfiguration::class)
class HealthManagementPortTest {

    @LocalServerPort
    private var serverPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `헬스는 관리 포트에서 인증 없이 열리고 본 포트에는 프로브 길만 남는다`() {
        // when
        val liveness = get(managementPort, "/actuator/health/liveness")
        val readiness = get(managementPort, "/actuator/health/readiness")
        val livez = get(serverPort, "/livez")
        val readyz = get(serverPort, "/readyz")
        val actuatorOnServerPort = get(serverPort, "/actuator/health")

        // then
        assertSoftly { softly ->
            softly.assertThat(managementPort).isNotEqualTo(serverPort)
            softly.assertThat(liveness.statusCode()).isEqualTo(200)
            softly.assertThat(liveness.body()).contains("\"UP\"")
            softly.assertThat(readiness.statusCode()).isEqualTo(200)
            softly.assertThat(livez.statusCode()).isEqualTo(200)
            softly.assertThat(readyz.statusCode()).isEqualTo(200)
            softly.assertThat(actuatorOnServerPort.statusCode()).isEqualTo(404)
        }
    }

    private fun get(port: Int, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
