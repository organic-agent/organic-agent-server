package com.soma.wes.auth.support

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI


@Component
class OAuthRedirectUriResolver(
    @Value("\${cors.allowed-origins}")
    private val allowedOrigins: List<String>,

    @Value("\${oauth.fallback-redirect-origin:}")
    private val fallbackOrigin: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(configuredRedirectUri: String, requestOrigin: String?): String {
        val origin = when {
            requestOrigin in allowedOrigins -> requestOrigin!!
            fallbackOrigin.isNotBlank() -> fallbackOrigin
            else -> return configuredRedirectUri
        }

        if (origin != requestOrigin) {
            log.debug("허용 목록에 없는 Origin({})이라 fallback({})으로 redirect-uri를 만든다", requestOrigin, origin)
        }

        return replaceOrigin(configuredRedirectUri, origin)
    }

    private fun replaceOrigin(redirectUri: String, origin: String): String {
        val parsed = URI(origin)

        return UriComponentsBuilder.fromUriString(redirectUri)
            .scheme(parsed.scheme)
            .host(parsed.host)
            // 포트가 없는 오리진(https 기본 포트)은 URI.getPort()가 -1을 준다.
            // UriComponentsBuilder는 -1을 "포트 없음"으로 읽으므로 그대로 넘기면 된다.
            .port(parsed.port)
            .build()
            .toUriString()
    }
}
