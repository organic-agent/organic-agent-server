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

    /**
     * @param configuredRedirectUri 설정(Parameter Store)에 적힌 redirect-uri. 경로는 여기서 가져온다.
     * @param requestOrigin 요청의 `Origin` 헤더. 없으면 null.
     */
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

    /**
     * 경로(`/login/oauth2/code/google`)는 그대로 두고 오리진만 갈아끼운다.
     * 경로까지 조합하지 않는 이유는 provider마다 콜백 경로가 다를 수 있고, 그건 설정이 정할 몫이기 때문이다.
     */
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
