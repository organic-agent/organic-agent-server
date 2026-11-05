package com.soma.wes.auth

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class StubOAuthClientConfig {

    @Bean
    @Primary
    fun stubOAuthClient(): StubOAuthClient = StubOAuthClient()
}
