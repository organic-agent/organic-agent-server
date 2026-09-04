package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import tools.jackson.databind.json.JsonMapper

class LambdaStageInvokerUnitTest {

    private val lambdaClient = mock<LambdaClient>()
    private val objectMapper = JsonMapper.builder().build()
    private val invoker = LambdaStageInvoker(
        lambdaClient,
        EmbeddingProperties(functionName = "wes-embedder"),
        AnalysisProperties(scoreFunctionName = "wes-score", categorizeFunctionName = ""),
    )

    @Test
    fun `단계마다 함수와 페이로드 계약이 다르다`() {
        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenReturn(InvokeResponse.builder().statusCode(202).build())

        invoker.invoke(AnalysisStage.EMBED, jobId = 7, galleryId = 3, force = true)
        invoker.invoke(AnalysisStage.SCORE, jobId = 7, galleryId = 3, force = false)

        val requests = argumentCaptor<InvokeRequest>()
        verify(lambdaClient, times(2)).invoke(requests.capture())
        val embed = requests.firstValue
        val score = requests.secondValue
        val embedPayload = objectMapper.readTree(embed.payload().asUtf8String())
        val scorePayload = objectMapper.readTree(score.payload().asUtf8String())
        assertSoftly { softly ->
            softly.assertThat(embed.functionName()).isEqualTo("wes-embedder")
            softly.assertThat(embed.invocationType()).isEqualTo(InvocationType.EVENT)
            // 임베더는 jobId 키를 관리자 사진 교체로 해석한다 — 갤러리 잡은 analysisJobId로 보낸다.
            softly.assertThat(embedPayload.has("jobId")).isFalse()
            softly.assertThat(embedPayload["analysisJobId"].asLong()).isEqualTo(7)
            softly.assertThat(embedPayload["galleryId"].asLong()).isEqualTo(3)
            softly.assertThat(embedPayload["force"].asBoolean()).isTrue()
            softly.assertThat(score.functionName()).isEqualTo("wes-score")
            softly.assertThat(scorePayload["jobId"].asLong()).isEqualTo(7)
            softly.assertThat(scorePayload["force"].asBoolean()).isFalse()
        }
    }

    @Test
    fun `함수 이름이 비어 있는 단계는 사용할 수 없다`() {
        assertSoftly { softly ->
            softly.assertThat(invoker.isAvailable(AnalysisStage.EMBED)).isTrue()
            softly.assertThat(invoker.isAvailable(AnalysisStage.SCORE)).isTrue()
            softly.assertThat(invoker.isAvailable(AnalysisStage.CATEGORIZE)).isFalse()
        }
    }

    @Test
    fun `SDK 예외와 202가 아닌 응답은 호출 실패 코드다`() {
        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenThrow(SdkClientException.create("no credentials"))
        assertThatThrownBy { invoker.invoke(AnalysisStage.SCORE, jobId = 1, galleryId = 1, force = false) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)

        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenReturn(InvokeResponse.builder().statusCode(500).build())
        assertThatThrownBy { invoker.invoke(AnalysisStage.SCORE, jobId = 1, galleryId = 1, force = false) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }
}
