package com.soma.wes.admin.resource

import com.soma.wes.admin.config.AdminObservabilityProperties
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.service.AdminObservabilityLinkService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdminObservabilityLinkServiceTest {

    @Test
    fun `동일 추적 아이디의 Grafana와 Loki 딥링크를 만든다`() {
        val service = AdminObservabilityLinkService(
            AdminObservabilityProperties(
                grafanaBaseUrl = "https://grafana.internal.example/",
                lokiBaseUrl = "http://10.0.0.8:3100/loki/api/v1/push",
            ),
        )

        val links = service.links("a1b2c3d4e5f60718")

        assertThat(links.grafanaUrl).startsWith("https://grafana.internal.example/explore?")
        assertThat(links.grafanaUrl).contains("a1b2c3d4e5f60718")
        assertThat(links.lokiUrl).startsWith("http://10.0.0.8:3100/loki/api/v1/query_range?")
        assertThat(links.lokiUrl).contains("a1b2c3d4e5f60718")
    }

    @Test
    fun `추적 아이디 형식을 검증하고 미구성 주소는 노출하지 않는다`() {
        val service = AdminObservabilityLinkService(AdminObservabilityProperties())

        val links = service.links("0123456789abcdef")
        assertThat(links.grafanaUrl).isNull()
        assertThat(links.lokiUrl).isNull()
        assertThatThrownBy { service.links("../secret") }.isInstanceOf(AdminException::class.java)
    }
}
