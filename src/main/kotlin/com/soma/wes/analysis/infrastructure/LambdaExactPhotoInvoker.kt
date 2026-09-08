package com.soma.wes.analysis.infrastructure

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.ExactPhotoInvoker
import com.soma.wes.analysis.service.ExactPhotoProcessingRequest
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
 * 관리자 사진 교체용 임베더 직접 호출 — 갤러리 배정([LambdaStageInvoker]의 Embed)과 같은 함수를 `{jobId, …}` 페이로드로 부른다.
 * 임베더는 `jobId` 키를 보고 관리자 사진 교체 이벤트로 해석한다. 로컬 프로필에서는 [LocalProcessExactPhotoInvoker]가 이 자리를 대신한다.
 */
@Component
@Profile("!local")
class LambdaExactPhotoInvoker(
    private val lambdaClient: LambdaClient,
    private val properties: AnalysisProperties,
    private val objectMapper: ObjectMapper,
) : ExactPhotoInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.isEmbedderConfigured

    override fun invoke(request: ExactPhotoProcessingRequest) {
        val functionName = properties.embedderFunctionName
        val payload = SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(request))
        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면 긴 작업을 HTTP 요청 하나가 붙들고 기다리게 된다.
                    .invocationType(InvocationType.EVENT)
                    .payload(payload)
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음). 둘을 같은 코드로
            // 돌려주면 "Lambda 로그를 볼 것"과 "IAM을 볼 것"을 구분할 수 없다.
            log.error("사진 처리 Lambda 호출 실패: jobId={}, function={}", request.jobId, functionName, e)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        if (statusCode != ACCEPTED) {
            log.error("사진 처리 Lambda가 비정상 응답을 반환함: jobId={}, function={}, status={}", request.jobId, functionName, statusCode)
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        log.info(
            "사진 처리 Lambda 호출: jobId={}, attempt={}, type={}, photoId={}, function={}, status={}",
            request.jobId,
            request.attemptCount,
            request.jobType,
            request.photoId,
            functionName,
            statusCode,
        )
    }

    companion object {
        /** EVENT 호출이 큐에 들어갔을 때 Lambda가 돌려주는 상태 코드. */
        private const val ACCEPTED = 202
    }
}
