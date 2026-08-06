package com.soma.wes.embedding.infrastructure

import com.soma.wes.embedding.config.EmbeddingProperties
import com.soma.wes.embedding.service.EmbeddingInvoker
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest


@Component
class LambdaEmbeddingInvoker(
    private val lambdaClient: LambdaClient,
    private val properties: EmbeddingProperties,
) : EmbeddingInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.isConfigured

    override fun invoke(galleryId: Long, force: Boolean) {
        // 페이로드를 문자열로 조립해도 안전한 이유: galleryId는 Long, force는 Boolean이라
        // 사용자 문자열이 끼어들 자리가 없다.
        val payload = """{"galleryId":$galleryId,"force":$force}"""

        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(properties.functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면
                    // 15분짜리 작업을 HTTP 요청 하나가 붙들고 기다리게 된다.
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(payload))
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음). 둘을 같은 코드로
            // 돌려주면 "Lambda 로그를 볼 것"과 "IAM을 볼 것"을 구분할 수 없다.
            log.error("임베딩 Lambda 호출 실패: galleryId={}, function={}", galleryId, properties.functionName, e)
            throw PhotoException(PhotoErrorCode.EMBEDDING_INVOCATION_FAILED)
        }

        // 함수 이름과 status는 이 어댑터 바깥에서 의미가 없다. 도메인 쪽 로그는 EmbeddingService가 남긴다.
        log.info("임베딩 Lambda 호출: galleryId={}, function={}, status={}", galleryId, properties.functionName, statusCode)
    }
}
