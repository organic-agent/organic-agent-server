package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.dto.StageCall
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.StageInvoker
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

/**
 * 운영 실행기 — 호출 종류마다 다른 Lambda 함수를 EVENT로 부른다. 로컬 프로필에서는 [LocalProcessStageInvoker]가 이 자리를 대신한다.
 *
 * 페이로드는 AI repo 계약 그대로다:
 * - embedder·score `{galleryId, photoIds}` — `jobId` 키는 임베더가 관리자 사진 교체 이벤트로 해석하므로 쓰지 않는다.
 * - categorize `{galleryId, jobId}`.
 */
@Component
@Profile("!local")
class LambdaStageInvoker(
    private val lambdaClient: LambdaClient,
    private val properties: AnalysisProperties,
) : StageInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(call: KClass<out StageCall>): Boolean = properties.functionNameOf(call).isNotBlank()

    override fun invoke(call: StageCall) {
        val functionName = properties.functionNameOf(call::class)
        val payload = payloadOf(call)

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
            log.error("분석 Lambda 호출 실패: call={}, function={}", call, functionName, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        if (statusCode != ACCEPTED) {
            log.error("분석 Lambda가 비정상 응답을 반환함: call={}, function={}, status={}", call, functionName, statusCode)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info("분석 Lambda 호출: {} function={}", describe(call), functionName)
    }

    companion object {
        /** EVENT 호출이 큐에 들어갔을 때 Lambda가 돌려주는 상태 코드. */
        private const val ACCEPTED = 202

        /** 페이로드를 문자열로 조립해도 안전한 이유: 전부 Long이라 사용자 문자열이 끼어들 자리가 없다. */
        fun payloadOf(call: StageCall): String = when (call) {
            is StageCall.Embed -> """{"galleryId":${call.galleryId},"photoIds":${call.photoIds.joinToString(",", "[", "]")}}"""
            is StageCall.Score -> """{"galleryId":${call.galleryId},"photoIds":${call.photoIds.joinToString(",", "[", "]")}}"""
            is StageCall.Categorize -> """{"galleryId":${call.galleryId},"jobId":${call.jobId}}"""
        }

        private fun describe(call: StageCall): String = when (call) {
            is StageCall.Embed -> "embed gallery=${call.galleryId} photos=${call.photoIds.size}"
            is StageCall.Score -> "score gallery=${call.galleryId} photos=${call.photoIds.size}"
            is StageCall.Categorize -> "categorize gallery=${call.galleryId} job=${call.jobId}"
        }
    }
}
