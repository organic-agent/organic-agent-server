package com.soma.wes.analysis.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * 분석 설정 세 클래스가 같은 prefix `app.analysis`를 나눠 가진다(#201). 키는 Parameter Store(`/wes/prod/app.analysis.*`)와의
 * 계약이라, 클래스를 나눠도 기존 키가 각자 제자리에 들어가야 한다.
 */
class AnalysisPropertiesBindingUnitTest {

    @TestConfiguration
    @EnableConfigurationProperties(
        AnalysisProperties::class,
        LambdaAiTaskProperties::class,
        LocalProcessProperties::class,
    )
    class AnalysisConfig

    @Test
    fun `기존 app analysis 키가 공통·Lambda·로컬 설정에 각자 바인딩된다`() {
        ApplicationContextRunner()
            .withUserConfiguration(AnalysisConfig::class.java)
            .withPropertyValues(
                "app.analysis.embedder-function-name=wes-embedder",
                "app.analysis.score-function-name=wes-score",
                "app.analysis.categorize-function-name=wes-categorize",
                "app.analysis.local-script-dir=scripts/lambda",
                "app.analysis.embed-batch-size=40",
                "app.analysis.gpu.enabled=true",
                "app.analysis.gpu.idle-stop-after=PT3M",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val analysis = context.getBean(AnalysisProperties::class.java)
                val lambda = context.getBean(LambdaAiTaskProperties::class.java)
                val local = context.getBean(LocalProcessProperties::class.java)
                assertSoftly { softly ->
                    softly.assertThat(lambda.embedderFunctionName).isEqualTo("wes-embedder")
                    softly.assertThat(lambda.scoreFunctionName).isEqualTo("wes-score")
                    softly.assertThat(lambda.categorizeFunctionName).isEqualTo("wes-categorize")
                    softly.assertThat(local.localScriptDir).isEqualTo("scripts/lambda")
                    softly.assertThat(analysis.embedBatchSize).isEqualTo(40)
                    softly.assertThat(analysis.gpu.enabled).isTrue()
                    softly.assertThat(analysis.gpu.idleStopAfter).isEqualTo(Duration.ofMinutes(3))
                }
            }
    }
}
