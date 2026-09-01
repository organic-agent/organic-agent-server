package com.soma.wes.recommendation.infrastructure

import com.soma.wes.recommendation.config.CompareProperties
import com.soma.wes.recommendation.dto.PairVerdictDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.service.PairCompareInvoker
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * 운영 실행기 — 비교샷 경량 Lambda를 `RequestResponse`로 부른다. 임베딩의 `EVENT` 호출과 달리
 * 사용자가 기다리는 동기 경로다(예산·폴백은 함수 안의 일이라 응답은 항상 판정이다).
 * 로컬 프로필에서는 [LocalProcessCompareInvoker]가 이 자리를 대신한다 — 실행기 빈은 프로필당 하나다.
 */
@Component
@Profile("!local")
class LambdaCompareInvoker(
    private val lambdaClient: LambdaClient,
    private val properties: CompareProperties,
    private val objectMapper: ObjectMapper,
) : PairCompareInvoker {

    private val log = LoggerFactory.getLogger(javaClass)

    override val isAvailable: Boolean
        get() = properties.isConfigured

    override fun compare(selectionId: Long, photoA: Long, photoB: Long): PairVerdictDto {
        // 전부 Long이라 사용자 문자열이 끼어들 자리가 없다. 키는 handler.py의 compare 계약이다.
        val payload = """{"mode":"compare","selectionId":$selectionId,"photoA":$photoA,"photoB":$photoB}"""

        val response = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(properties.functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromUtf8String(payload))
                    .build(),
            )
        } catch (e: SdkException) {
            log.error("비교샷 Lambda 호출 실패: selectionId={}, function={}", selectionId, properties.functionName, e)
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }
        // 함수 안 예외는 200으로 돌아온다 — functionError가 유일한 판별이다.
        if (response.functionError() != null) {
            log.error(
                "비교샷 Lambda 함수 오류: selectionId={}, function={}, error={}, payload={}",
                selectionId, properties.functionName, response.functionError(), response.payload().asUtf8String(),
            )
            throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
        }

        return parse(response.payload().asUtf8String(), selectionId)
    }

    private fun parse(json: String, selectionId: Long): PairVerdictDto = try {
        objectMapper.readValue(json, PairVerdictDto::class.java)
    } catch (e: JacksonException) {
        log.error("비교샷 응답이 계약을 벗어남: selectionId={}, payload={}", selectionId, json, e)
        throw RecommendationException(RecommendationErrorCode.COMPARE_FAILED)
    }
}
