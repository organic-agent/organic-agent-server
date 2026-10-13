package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.config.LambdaAiTaskProperties
import com.soma.wes.analysis.dto.AiFailureDto
import com.soma.wes.analysis.dto.AiFailureMessageDto
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.AiFailureQueue
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * SQS 실패 큐 어댑터. 메시지 본문은 Lambda 비동기 호출 기록(destination record)이다 — AI 저장소가 만드는 것이 아니라 Lambda 가 만든다:
 * ```
 * {"timestamp": "...Z", "requestContext": {"functionArn": "...:function:wes-dev-score:$LATEST", "condition": "RetriesExhausted", ...},
 *  "requestPayload": {보냈던 페이로드}, "responsePayload": {"errorType": "ValueError", "errorMessage": "..."}}
 * ```
 * 어느 함수인지는 [LambdaAiTaskProperties]의 함수 이름으로 가르고, 같은 함수 안의 작업(categorize 의 분류·rank, 임베더의 배정·사진 교체)은
 * 페이로드 키로 가른다 — 보낼 때의 규칙([LambdaAiTaskSender])과 짝이다. 읽을 수 없는 메시지는 `failure = null`로 넘겨 호출자가 기록하고 지운다.
 */
@Component
@ConditionalOnExpression("!'\${app.analysis.failure-queue.queue-url:}'.isBlank()")
class SqsAiFailureQueue(
    private val sqsClient: SqsClient,
    private val properties: AnalysisProperties,
    private val functions: LambdaAiTaskProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AiFailureQueue {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun receive(): List<AiFailureMessageDto> {
        val response = try {
            sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(properties.failureQueue.queueUrl)
                    .maxNumberOfMessages(MAX_MESSAGES)
                    .waitTimeSeconds(properties.failureQueue.waitTime.seconds.toInt())
                    .build(),
            )
        } catch (e: SdkException) {
            log.warn("failure queue receive failed: {}", e.message)
            throw AnalysisException(AnalysisErrorCode.FAILURE_QUEUE_UNAVAILABLE)
        }
        return response.messages().map { message -> AiFailureMessageDto(message.receiptHandle(), parse(message.body())) }
    }

    override fun delete(receiptHandle: String) {
        try {
            sqsClient.deleteMessage(
                DeleteMessageRequest.builder().queueUrl(properties.failureQueue.queueUrl).receiptHandle(receiptHandle).build(),
            )
        } catch (e: SdkException) {
            log.warn("failure queue delete failed: {}", e.message)
            throw AnalysisException(AnalysisErrorCode.FAILURE_QUEUE_UNAVAILABLE)
        }
    }

    /** 본문 하나를 읽는다. 모양이 다르면 null — 경고 한 줄과 본문 앞부분을 남긴다. */
    fun parse(body: String): AiFailureDto? = try {
        val record = objectMapper.readTree(body)
        val context = record.path("requestContext")
        val functionName = context.path("functionArn").asString("").substringAfter(":function:").substringBefore(":")
        val response = record.path("responsePayload")
        AiFailureDto(
            task = taskOf(functionName, record.path("requestPayload")),
            functionName = functionName,
            condition = context.path("condition").asString(""),
            errorType = response.path("errorType").takeIf { it.isString }?.asString(),
            errorMessage = response.path("errorMessage").takeIf { it.isString }?.asString()?.take(MAX_ERROR_MESSAGE),
            failedAt = Instant.parse(record.path("timestamp").asString()).atZone(clock.zone),
        )
    } catch (e: RuntimeException) {
        log.warn("failure queue message unreadable: {} body={}", e.javaClass.simpleName, body.take(MAX_LOGGED_BODY))
        null
    }

    private fun taskOf(functionName: String, payload: JsonNode): AiTaskDto? {
        if (!payload.isObject || !payload.has("galleryId")) return null
        val type = when (functionName) {
            functions.embedderFunctionName -> if (payload.has("photoIds")) AiTaskDto.Embed::class else AiTaskDto.ExactPhoto::class
            functions.scoreFunctionName -> AiTaskDto.Score::class
            functions.categorizeFunctionName ->
                if (payload.path("mode").asString("") == AiTaskDto.Rank.RANK_MODE) {
                    return AiTaskDto.Rank(galleryId = payload.path("galleryId").asLong())
                } else {
                    AiTaskDto.Categorize::class
                }
            else -> return null
        }
        return objectMapper.treeToValue(payload, type.java)
    }

    companion object {
        /** SQS 한 번에 받는 메시지 상한. */
        private const val MAX_MESSAGES = 10

        /** 기록에 남기는 오류 문구 길이. 스택이 실려 오는 경우가 있다. */
        private const val MAX_ERROR_MESSAGE = 500

        /** 읽을 수 없는 메시지를 로그에 남기는 길이. */
        private const val MAX_LOGGED_BODY = 300
    }
}
