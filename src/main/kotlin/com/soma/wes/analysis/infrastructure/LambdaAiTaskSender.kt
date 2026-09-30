package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.AiTaskSender
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import tools.jackson.databind.ObjectMapper

/**
 * 운영 실행기 — 호출 종류마다 다른 Lambda 함수를 EVENT로 부른다. 로컬 프로필에서는 [LocalAiTaskSender]가 이 자리를 대신한다.
 *
 * 페이로드는 [AiTaskDto]를 그대로 직렬화한 것이다 — 프로퍼티 이름이 곧 AI repo 계약의 키다:
 * - embedder·score `{galleryId, photoIds}` — `jobId` 키는 임베더가 관리자 사진 교체 이벤트로 해석하므로 배정에는 없다.
 * - categorize `{galleryId, jobId}`.
 * - exact photo(관리자 사진 교체) `{jobId, attemptCount, jobType, photoId, galleryId, storageKey, revisionId}` — embedder 함수로 간다.
 */
@Component
@Profile("!local")
class LambdaAiTaskSender(
    private val lambdaClient: LambdaClient,
    private val properties: AnalysisProperties,
    private val objectMapper: ObjectMapper,
) : AiTaskSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(task: KClass<out AiTaskDto>): Boolean = properties.functionNameOf(task).isNotBlank()

    override fun send(task: AiTaskDto) {
        val functionName = properties.functionNameOf(task::class)
        val payload = objectMapper.writeValueAsBytes(task)

        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면 15분짜리 작업을 스윕 스레드가 붙들고 기다린다.
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromByteArray(payload))
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음).
            log.error("분석 Lambda 호출 실패: task={}, function={}", task, functionName, e)
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        if (statusCode != ACCEPTED) {
            log.error("분석 Lambda가 비정상 응답을 반환함: task={}, function={}, status={}", task, functionName, statusCode)
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        log.info("분석 Lambda 호출: {} function={}", describe(task), functionName)
    }

    companion object {
        /** EVENT 호출이 큐에 들어갔을 때 Lambda가 돌려주는 상태 코드. */
        private const val ACCEPTED = 202

        private fun describe(task: AiTaskDto): String = when (task) {
            is AiTaskDto.Embed -> "embed gallery=${task.galleryId} photos=${task.photoIds.size}"
            is AiTaskDto.Score -> "score gallery=${task.galleryId} photos=${task.photoIds.size}"
            is AiTaskDto.Categorize -> "categorize gallery=${task.galleryId} job=${task.jobId}"
            is AiTaskDto.ExactPhoto -> "exact-photo gallery=${task.galleryId} photo=${task.photoId} job=${task.jobId} attempt=${task.attemptCount} type=${task.jobType}"
        }
    }
}
