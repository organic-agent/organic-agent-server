package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.config.LambdaAiTaskProperties
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** 실패 큐 메시지(Lambda 비동기 호출 기록)를 어떤 작업의 실패로 읽는지. 본문 모양은 dev 에서 강제 실패로 받은 실제 메시지(2026-10-14)를 따랐다. */
class SqsAiFailureQueueUnitTest {

    private val sqsClient = mock<SqsClient>()
    private val queue = SqsAiFailureQueue(
        sqsClient,
        AnalysisProperties(failureQueue = AnalysisProperties.FailureQueue(queueUrl = "https://sqs.example/wes-dev-analysis-failures")),
        LambdaAiTaskProperties(embedderFunctionName = "wes-dev-embedder", scoreFunctionName = "wes-dev-score", categorizeFunctionName = "wes-dev-categorize"),
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(),
        Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneId.of("Asia/Seoul")),
    )

    private fun record(function: String, payload: String, condition: String = "RetriesExhausted") = """
        {"version":"1.0","timestamp":"2026-10-13T16:01:02.345Z",
         "requestContext":{"requestId":"d05956ab","functionArn":"arn:aws:lambda:ap-northeast-2:1:function:$function:${'$'}LATEST",
                           "condition":"$condition","approximateInvokeCount":1},
         "requestPayload":$payload,
         "responseContext":{"statusCode":200,"executedVersion":"${'$'}LATEST","functionError":"Unhandled"},
         "responsePayload":{"errorMessage":"페이로드에 galleryId가 없습니다","errorType":"ValueError","stackTrace":[]}}
    """.trimIndent()

    @Test
    fun `함수 이름과 페이로드 키로 어떤 작업이 실패했는지 가른다`() {
        // when
        val embed = queue.parse(record("wes-dev-embedder", """{"galleryId":3,"photoIds":[10,11]}"""))
        val score = queue.parse(record("wes-dev-score", """{"galleryId":3,"photoIds":[12]}"""))
        val categorize = queue.parse(record("wes-dev-categorize", """{"galleryId":3,"jobId":7}""", condition = "EventAgeExceeded"))
        val rank = queue.parse(record("wes-dev-categorize", """{"galleryId":3,"mode":"rank"}"""))

        // then
        assertSoftly { softly ->
            softly.assertThat(embed?.task).isEqualTo(AiTaskDto.Embed(galleryId = 3, photoIds = listOf(10, 11)))
            softly.assertThat(score?.task).isEqualTo(AiTaskDto.Score(galleryId = 3, photoIds = listOf(12)))
            softly.assertThat(categorize?.task).isEqualTo(AiTaskDto.Categorize(galleryId = 3, jobId = 7))
            softly.assertThat(categorize?.condition).isEqualTo("EventAgeExceeded")
            softly.assertThat(rank?.task).isEqualTo(AiTaskDto.Rank(galleryId = 3))
        }
    }

    @Test
    fun `실패 사유와 오류와 실패 시각을 옮긴다`() {
        // when
        val failure = queue.parse(record("wes-dev-score", """{"galleryId":3,"photoIds":[12]}""")) ?: error("읽혀야 한다")

        // then
        assertSoftly { softly ->
            softly.assertThat(failure.functionName).isEqualTo("wes-dev-score")
            softly.assertThat(failure.condition).isEqualTo("RetriesExhausted")
            softly.assertThat(failure.errorType).isEqualTo("ValueError")
            softly.assertThat(failure.errorMessage).isEqualTo("페이로드에 galleryId가 없습니다")
            softly.assertThat(failure.failedAt.toInstant()).isEqualTo(Instant.parse("2026-10-13T16:01:02.345Z"))
        }
    }

    @Test
    fun `모르는 함수나 갤러리가 없는 페이로드는 작업 없이 넘기고 본문이 깨졌으면 null 이다`() {
        // when
        val otherFunction = queue.parse(record("someone-else", """{"galleryId":3,"photoIds":[1]}"""))
        val probe = queue.parse(record("wes-dev-score", """{"probe":"infra#99 failure-queue check"}"""))
        val broken = queue.parse("not json")

        // then
        assertSoftly { softly ->
            softly.assertThat(otherFunction).isNotNull
            softly.assertThat(otherFunction?.task).isNull()
            softly.assertThat(probe?.task).isNull()
            softly.assertThat(broken).isNull()
        }
    }

    @Test
    fun `받은 메시지는 지울 손잡이와 함께 넘기고 큐를 못 읽으면 도메인 예외다`() {
        // given
        val body = record("wes-dev-score", """{"galleryId":3,"photoIds":[12]}""")
        whenever(sqsClient.receiveMessage(any<ReceiveMessageRequest>()))
            .thenReturn(ReceiveMessageResponse.builder().messages(Message.builder().receiptHandle("rh-1").body(body).build()).build())
            .thenThrow(SdkClientException.create("no network"))

        // when
        val messages = queue.receive()

        // then
        assertThat(messages.single().receiptHandle).isEqualTo("rh-1")
        assertThatThrownBy { queue.receive() }
            .isInstanceOfSatisfying(AnalysisException::class.java) { e -> assertThat(e.errorCode).isEqualTo(AnalysisErrorCode.FAILURE_QUEUE_UNAVAILABLE) }
    }
}
