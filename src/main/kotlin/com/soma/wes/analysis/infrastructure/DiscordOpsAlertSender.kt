package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.OpsAlertProperties
import com.soma.wes.analysis.dto.OpsAlertDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.OpsAlertSender
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 디스코드 채널 웹훅으로 운영 알림을 보낸다. 웹훅은 `{"content": "..."}` 하나를 POST 하면 채널에 글이 올라간다.
 * 모든 프로필에서 쓰인다 — 주소가 비어 있으면(로컬·테스트) [isAvailable]이 false 다.
 */
@Component
class DiscordOpsAlertSender(
    private val properties: OpsAlertProperties,
    private val restClient: RestClient,
) : OpsAlertSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(): Boolean = properties.isConfigured

    override fun send(alert: OpsAlertDto) {
        val content = render(alert)
        try {
            restClient.post()
                .uri(properties.discordWebhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .body(mapOf("content" to content))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientException) {
            log.warn("디스코드 웹훅 전송 실패: {}", e.message)
            throw AnalysisException(AnalysisErrorCode.OPS_ALERT_SEND_FAILED)
        }
    }

    private fun render(alert: OpsAlertDto): String =
        (listOf("**${alert.title}**") + alert.lines).joinToString("\n").take(MAX_CONTENT_LENGTH)

    companion object {
        /** 디스코드 메시지 `content`의 최대 글자 수. 넘으면 웹훅이 400 으로 거절한다. */
        private const val MAX_CONTENT_LENGTH = 2000

        /** 디스코드 앞단(Cloudflare)은 기본 Java·Python User-Agent 요청을 막기도 한다. 보내는 쪽이 누구인지 밝힌다. */
        private const val USER_AGENT = "wes-ops-alert"
    }
}
