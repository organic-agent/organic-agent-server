package com.soma.wes.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** 통합 테스트 전원이 공유하는 LLM·미리보기·잡 실행기·GPU 워커 풀 페이크. `@IntegrationTest`가 import한다. */
@TestConfiguration(proxyBeanMethods = false)
class FakeLlmConfig {

    @Bean
    @Primary
    fun fakeStructuredLlmClient(): FakeStructuredLlmClient = FakeStructuredLlmClient()

    @Bean
    @Primary
    fun fakePreviewImageReader(): FakePreviewImageReader = FakePreviewImageReader()

    @Bean
    @Primary
    fun manualAiJobExecutor(): ManualAiJobExecutor = ManualAiJobExecutor()

    @Bean
    @Primary
    fun fakeStageInvoker(): FakeStageInvoker = FakeStageInvoker()

    @Bean
    @Primary
    fun manualScoreWorkerPool(): ManualScoreWorkerPool = ManualScoreWorkerPool()
}
