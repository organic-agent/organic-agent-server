package com.soma.wes.global.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * OpenAI 호환 게이트웨이(`app.llm.provider=openai`)를 부르는 RestClient. 셀렉 도우미와 보정 요청 다듬기가 같이 쓴다.
 * 읽기 타임아웃은 두 호출 중 긴 쪽(보정 요청 다듬기 20초)에 맞춘다 — 판단기는 그보다 먼저 끝난다.
 */
@Configuration
class OpenAiLlmConfig {

    companion object {

        const val REST_CLIENT = "openAiLlmRestClient"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    @Bean(REST_CLIENT)
    @ConditionalOnProperty(prefix = "app.llm", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "openai")
    fun openAiLlmRestClient(properties: LlmProperties): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(READ_TIMEOUT) }
        return RestClient.builder()
            .baseUrl(properties.openai.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.openai.apiKey}")
            .requestFactory(requestFactory)
            .build()
    }
}
