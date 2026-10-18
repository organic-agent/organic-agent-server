package com.soma.wes.photo

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock

/** 모든 통합 테스트가 같은 [SwitchablePreviewCdn]을 나눈다(`@IntegrationTest`의 `@Import`). */
@TestConfiguration(proxyBeanMethods = false)
class FakePreviewCdnConfig {

    @Bean
    @Primary
    fun switchablePreviewCdn(clock: Clock): SwitchablePreviewCdn = SwitchablePreviewCdn(clock)
}
