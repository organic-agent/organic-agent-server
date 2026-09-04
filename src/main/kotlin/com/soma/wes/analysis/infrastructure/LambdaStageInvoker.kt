package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.StageInvoker
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

/**
 * 운영 실행기 — 단계마다 다른 Lambda 함수를 EVENT로 부른다. 로컬 프로필에서는 [LocalProcessStageInvoker]가 이 자리를 대신한다.
 *
 * 페이로드는 각 Lambda의 현재 계약 그대로다:
 * - embedder `{galleryId, force, analysisJobId}` — `jobId` 키는 관리자 사진 교체 이벤트로 해석되므로 여기서는 쓰지 않는다.
 * - score `{galleryId, jobId, force}`, categorize `{galleryId, jobId}`.
 */
@Component
@Profile("!local")
class LambdaStageInvoker(
    private val lambdaClient: LambdaClient,
    private val embeddingProperties: EmbeddingProperties,
    private val analysisProperties: AnalysisProperties,
) : StageInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(stage: AnalysisStage): Boolean = functionNameOf(stage).isNotBlank()

    override fun invoke(stage: AnalysisStage, jobId: Long, galleryId: Long, force: Boolean) {
        val functionName = functionNameOf(stage)
        // 페이로드를 문자열로 조립해도 안전한 이유: 전부 Long·Boolean이라 사용자 문자열이 끼어들 자리가 없다.
        val payload = when (stage) {
            AnalysisStage.EMBED -> """{"galleryId":$galleryId,"force":$force,"analysisJobId":$jobId}"""
            AnalysisStage.SCORE -> """{"galleryId":$galleryId,"jobId":$jobId,"force":$force}"""
            AnalysisStage.CATEGORIZE -> """{"galleryId":$galleryId,"jobId":$jobId}"""
        }

        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면 15분짜리 작업을 스윕 스레드가 붙들고 기다린다.
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(payload))
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음).
            log.error("분석 Lambda 호출 실패: stage={}, jobId={}, function={}", stage, jobId, functionName, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        if (statusCode != ACCEPTED) {
            log.error("분석 Lambda가 비정상 응답을 반환함: stage={}, jobId={}, function={}, status={}", stage, jobId, functionName, statusCode)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info("분석 Lambda 호출: stage={}, jobId={}, galleryId={}, function={}", stage, jobId, galleryId, functionName)
    }

    private fun functionNameOf(stage: AnalysisStage): String = when (stage) {
        AnalysisStage.EMBED -> embeddingProperties.functionName
        AnalysisStage.SCORE -> analysisProperties.scoreFunctionName
        AnalysisStage.CATEGORIZE -> analysisProperties.categorizeFunctionName
    }

    companion object {
        /** EVENT 호출이 큐에 들어갔을 때 Lambda가 돌려주는 상태 코드. */
        private const val ACCEPTED = 202
    }
}
