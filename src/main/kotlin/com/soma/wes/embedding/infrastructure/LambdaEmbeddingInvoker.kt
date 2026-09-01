package com.soma.wes.embedding.infrastructure

import com.soma.wes.embedding.config.EmbeddingProperties
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.embedding.service.ExactPhotoProcessingRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
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
 * 운영 실행기. 로컬 프로필에서는 [LocalProcessEmbeddingInvoker]가 이 자리를 대신한다 —
 * 실행기 빈은 프로필당 하나다.
 */
@Component
@Profile("!local")
class LambdaEmbeddingInvoker(
    private val lambdaClient: LambdaClient,
    private val properties: EmbeddingProperties,
    private val objectMapper: ObjectMapper,
) : EmbeddingInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.isConfigured

    override fun invoke(galleryId: Long, force: Boolean) {
        // 페이로드를 문자열로 조립해도 안전한 이유: galleryId는 Long, force는 Boolean이라
        // 사용자 문자열이 끼어들 자리가 없다.
        val payload = """{"galleryId":$galleryId,"force":$force}"""

        val statusCode = invokePayload(SdkBytes.fromUtf8String(payload), "galleryId=$galleryId")
        log.info("임베딩 Lambda 호출: galleryId={}, function={}, status={}", galleryId, properties.functionName, statusCode)
    }

    override fun invoke(request: ExactPhotoProcessingRequest) {
        val payload = SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(request))
        val statusCode = invokePayload(payload, "jobId=${request.jobId}")
        log.info(
            "사진 처리 Lambda 호출: jobId={}, attempt={}, type={}, photoId={}, function={}, status={}",
            request.jobId,
            request.attemptCount,
            request.jobType,
            request.photoId,
            properties.functionName,
            statusCode,
        )
    }

    private fun invokePayload(payload: SdkBytes, target: String): Int {
        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(properties.functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면
                    // 15분짜리 작업을 HTTP 요청 하나가 붙들고 기다리게 된다.
                    .invocationType(InvocationType.EVENT)
                    .payload(payload)
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음). 둘을 같은 코드로
            // 돌려주면 "Lambda 로그를 볼 것"과 "IAM을 볼 것"을 구분할 수 없다.
            log.error("사진 처리 Lambda 호출 실패: {}, function={}", target, properties.functionName, e)
            throw PhotoException(PhotoErrorCode.EMBEDDING_INVOCATION_FAILED)
        }
        if (statusCode != 202) {
            log.error("사진 처리 Lambda가 비정상 응답을 반환함: {}, function={}, status={}", target, properties.functionName, statusCode)
            throw PhotoException(PhotoErrorCode.EMBEDDING_INVOCATION_FAILED)
        }
        return statusCode
    }
}
