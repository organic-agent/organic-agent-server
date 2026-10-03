package com.soma.wes.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 운영 알림 어댑터(`DiscordOpsAlertSender`)만 쓰는 값. prod는 Parameter Store(`/wes/prod/app.analysis.discord-webhook-url`)가 채운다.
 *
 * prefix가 [AnalysisProperties]와 같아 설정 키는 `app.analysis.discord-webhook-url` 그대로다. 웹훅 주소는 아는 사람 누구나 그 채널에
 * 글을 쓸 수 있는 비밀값이라 저장소에 두지 않는다. 비어 있으면 알림 대신 로그만 남는다.
 */
@ConfigurationProperties(prefix = "app.analysis")
data class OpsAlertProperties(
    val discordWebhookUrl: String = "",
) {

    val isConfigured: Boolean
        get() = discordWebhookUrl.isNotBlank()
}
