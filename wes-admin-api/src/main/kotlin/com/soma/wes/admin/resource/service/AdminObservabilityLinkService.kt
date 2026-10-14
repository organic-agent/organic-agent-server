package com.soma.wes.admin.resource.service

import com.soma.wes.admin.config.AdminObservabilityProperties
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.dto.AdminObservabilityLinksResponse
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class AdminObservabilityLinkService(
    private val properties: AdminObservabilityProperties,
) {

    fun links(correlationId: String): AdminObservabilityLinksResponse {
        val normalized = correlationId.trim()
        if (!CORRELATION_ID.matches(normalized)) {
            throw AdminException(AdminErrorCode.INVALID_RESOURCE_FIELDS)
        }
        val lokiQuery = "{service=\"wes-admin-api\",env=\"prod\"} | logfmt | traceId=\"$normalized\""
        val encodedQuery = encode(lokiQuery)
        val grafanaState = """{"datasource":"Loki","queries":[{"refId":"A","expr":"$lokiQuery"}]}"""

        return AdminObservabilityLinksResponse(
            correlationId = normalized,
            grafanaUrl = properties.grafanaBaseUrl.takeIf { properties.grafanaConfigured }
                ?.trimEnd('/')
                ?.let { "$it/explore?orgId=1&left=${encode(grafanaState)}" },
            // 이 base URL은 private Loki 주소가 아니라 Grafana의 server-side datasource
            // proxy(uid=loki)다. 운영자 브라우저에는 기존 Grafana 인증과 HTTPS가 적용된다.
            lokiUrl = properties.lokiBaseUrl.takeIf { properties.lokiConfigured }
                ?.trimEnd('/')
                ?.let { "$it/loki/api/v1/query_range?query=$encodedQuery" },
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private val CORRELATION_ID = Regex("^[a-f0-9]{16}$")
    }
}
