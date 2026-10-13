package com.soma.wes.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.admin.observability")
data class AdminObservabilityProperties(
    val grafanaBaseUrl: String = "",
    val lokiBaseUrl: String = "",
) {
    val grafanaConfigured: Boolean
        get() = grafanaBaseUrl.isHttpUrl()

    val lokiConfigured: Boolean
        get() = lokiBaseUrl.isHttpUrl()

    private fun String.isHttpUrl(): Boolean =
        runCatching { java.net.URI(this).scheme in setOf("http", "https") }.getOrDefault(false)
}
