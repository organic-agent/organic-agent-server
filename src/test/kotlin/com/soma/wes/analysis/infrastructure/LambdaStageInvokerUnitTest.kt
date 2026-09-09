package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.StageCallDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
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
        AnalysisProperties(embedderFunctionName = "wes-embedder", scoreFunctionName = "wes-score", categorizeFunctionName = ""),
    )

    @Test
    fun `호출 종류마다 함수와 페이로드 계약이 다르다`() {
        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenReturn(InvokeResponse.builder().statusCode(202).build())

        invoker.invoke(StageCallDto.Embed(galleryId = 3, photoIds = listOf(10, 11, 12)))
        invoker.invoke(StageCallDto.Score(galleryId = 3, photoIds = listOf(10)))

        val requests = argumentCaptor<InvokeRequest>()
        verify(lambdaClient, times(2)).invoke(requests.capture())
        val embed = requests.firstValue
        val score = requests.secondValue
        val embedPayload = objectMapper.readTree(embed.payload().asUtf8String())
        val scorePayload = objectMapper.readTree(score.payload().asUtf8String())
        assertSoftly { softly ->
            softly.assertThat(embed.functionName()).isEqualTo("wes-embedder")
            softly.assertThat(embed.invocationType()).isEqualTo(InvocationType.EVENT)
            // 임베더는 jobId 키를 관리자 사진 교체로 해석한다 — 배정 페이로드에는 갤러리와 사진 목록뿐이다.
            softly.assertThat(embedPayload.has("jobId")).isFalse()
            softly.assertThat(embedPayload["galleryId"].asLong()).isEqualTo(3)
            softly.assertThat(embedPayload["photoIds"].toString()).isEqualTo("[10,11,12]")
            softly.assertThat(score.functionName()).isEqualTo("wes-score")
            softly.assertThat(scorePayload["galleryId"].asLong()).isEqualTo(3)
            softly.assertThat(scorePayload["photoIds"].toString()).isEqualTo("[10]")
            softly.assertThat(scorePayload.has("jobId")).isFalse()
        }
    }

    @Test
    fun `categorize 페이로드는 갤러리와 잡 id다`() {
        val payload = objectMapper.readTree(LambdaStageInvoker.payloadOf(StageCallDto.Categorize(galleryId = 8, jobId = 13)))
        assertSoftly { softly ->
            softly.assertThat(payload["galleryId"].asLong()).isEqualTo(8)
            softly.assertThat(payload["jobId"].asLong()).isEqualTo(13)
            softly.assertThat(payload.has("photoIds")).isFalse()
        }
    }

    @Test
    fun `함수 이름이 비어 있는 호출은 사용할 수 없다`() {
        assertSoftly { softly ->
            softly.assertThat(invoker.isAvailable(StageCallDto.Embed::class)).isTrue()
            softly.assertThat(invoker.isAvailable(StageCallDto.Score::class)).isTrue()
            softly.assertThat(invoker.isAvailable(StageCallDto.Categorize::class)).isFalse()
        }
    }

    @Test
    fun `SDK 예외와 202가 아닌 응답은 호출 실패 코드다`() {
        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenThrow(SdkClientException.create("no credentials"))
        assertThatThrownBy { invoker.invoke(StageCallDto.Score(galleryId = 1, photoIds = listOf(1))) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)

        whenever(lambdaClient.invoke(any<InvokeRequest>())).thenReturn(InvokeResponse.builder().statusCode(500).build())
        assertThatThrownBy { invoker.invoke(StageCallDto.Score(galleryId = 1, photoIds = listOf(1))) }
            .isInstanceOf(AnalysisException::class.java)
            .extracting("errorCode")
            .isEqualTo(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
    }
}
