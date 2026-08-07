package com.soma.wes.auth.infrastructure

import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.auth.service.oauth.OAuthClient
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@Component
class OAuthRestClient(
    private val restClient: RestClient,
) : OAuthClient {

    companion object {
        private val MAP_TYPE = object : ParameterizedTypeReference<Map<String, Any>>() {}
    }

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getAccessTokenResponse(
        tokenUri: String,
        tokenRequest: MultiValueMap<String, String>,
    ): Map<String, Any> = execute(AuthErrorCode.AUTH_CODE_INVALID) {
        restClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(tokenRequest)
            .retrieve()
            .body(MAP_TYPE)
    }

    override fun getUserInfoWithAccessToken(
        userInfoUri: String,
        accessToken: String,
    ): Map<String, Any> = execute(AuthErrorCode.OAUTH_ACCESS_TOKEN_INVALID) {
        restClient.get()
            .uri(userInfoUri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .retrieve()
            .body(MAP_TYPE)
    }

    /**
     * @param badRequestErrorCode provider가 4xx를 돌려준 경우의 원인. 토큰 요청이면 인가 코드가,
     *                            사용자 정보 요청이면 access token이 잘못된 것이다.
     */
    private fun execute(
        badRequestErrorCode: AuthErrorCode,
        request: () -> Map<String, Any>?,
    ): Map<String, Any> =
        try {
            request() ?: throw OAuthException(AuthErrorCode.OAUTH_RESPONSE_INVALID)
        } catch (e: HttpClientErrorException) {
            log.warn("소셜 로그인 서버가 요청을 거부했습니다: {}", e.message)
            throw OAuthException(badRequestErrorCode)
        } catch (e: ResourceAccessException) {
            // 네트워크 장애·타임아웃. provider 잘못이 아니라 우리 쪽에서 닿지 못한 것이다.
            log.warn("소셜 로그인 서버에 접속하지 못했습니다", e)
            throw OAuthException(AuthErrorCode.OAUTH_SERVER_ERROR)
        } catch (e: RestClientException) {
            log.error("소셜 로그인 서버 통신 오류", e)
            throw OAuthException(AuthErrorCode.OAUTH_SERVER_ERROR)
        }
}
