package com.soma.wes.global

import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * ALB 는 liveness 만, 배포 스크립트는 DB 까지 보는 readiness 로 본다(#313 ADR-0001 §7 안건 1). liveness 에 DB 가 끼면 DB 하나가 흔들릴 때
 * 서버가 다 같이 빠지고 ASG 가 멀쩡한 서버를 갈아 치운다. PublicPaths 에서 프로브 경로가 빠지면 401 이라 여기서 걸린다.
 */
@IntegrationTest
class HealthProbeTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val healthGroups: HealthEndpointGroups,
) {

    @Test
    fun `프로브는 인증 없이 본 포트에서 열린다`() {
        for (path in listOf("/livez", "/readyz", "/actuator/health/liveness", "/actuator/health/readiness")) {
            mockMvc.get(path).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
        }
    }

    @Test
    fun `liveness 는 DB 를 보지 않고 readiness 는 DB 를 본다`() {
        // when
        val liveness = checkNotNull(healthGroups.get("liveness")) { "probes 가 꺼져 liveness 그룹이 없다" }
        val readiness = checkNotNull(healthGroups.get("readiness")) { "probes 가 꺼져 readiness 그룹이 없다" }

        // then
        assertSoftly { softly ->
            softly.assertThat(liveness.isMember("db")).isFalse()
            softly.assertThat(liveness.isMember("livenessState")).isTrue()
            softly.assertThat(readiness.isMember("db")).isTrue()
            softly.assertThat(readiness.isMember("readinessState")).isTrue()
        }
    }
}
