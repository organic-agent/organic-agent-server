package com.soma.wes.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 임베더 Lambda. 운영 함수 이름 하나뿐이다 — 로컬 대역 스크립트는 [AnalysisProperties.localScriptDir]가 세 단계를 함께 가리킨다.
 * 비어 있는 것은 오류가 아니다. 테스트에는 실행기가 없는 것이 정상이라 기동을 막지 않고, 실제로 부르려는 순간에 실패한다.
 */
@ConfigurationProperties(prefix = "app.embedding")
data class EmbeddingProperties(
    val functionName: String,
) {

    val isConfigured: Boolean
        get() = functionName.isNotBlank()
}
