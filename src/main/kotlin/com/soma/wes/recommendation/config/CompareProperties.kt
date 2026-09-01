package com.soma.wes.recommendation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 비교샷 판정을 계산하는 실행기.
 *
 * 운영은 경량 Lambda([functionName] — 분석 배치와 달리 torch 없이 DB·미리보기·Bedrock만 쓴다),
 * 로컬은 AI repo CLI를 서브프로세스로 띄우는 스크립트([localScript])다. 둘 다 비어 있는 것은
 * 오류가 아니다 — 테스트에는 실행기가 없는 것이 정상이라 기동을 막지 않고, 부르는 순간 실패한다.
 */
@ConfigurationProperties(prefix = "app.compare")
data class CompareProperties(
    val functionName: String = "",
    val localScript: String = "",
) {
    val isConfigured: Boolean
        get() = functionName.isNotBlank()

    val isLocalConfigured: Boolean
        get() = localScript.isNotBlank()
}
