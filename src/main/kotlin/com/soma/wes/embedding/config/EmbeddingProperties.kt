package com.soma.wes.embedding.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 임베딩을 계산하는 Lambda.
 *
 * [functionName]이 비어 있는 것은 오류가 아니다. 로컬·테스트에는 함수가 없는 것이 정상이라
 * 기동을 막지 않고, 실제로 부르려는 순간에 실패한다.
 */
@ConfigurationProperties(prefix = "app.embedding")
data class EmbeddingProperties(
    val functionName: String,
) {
    val isConfigured: Boolean
        get() = functionName.isNotBlank()
}
