package com.soma.wes.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 임베더 실행기.
 *
 * 운영은 Lambda([functionName]), 로컬은 노트북에서 AI repo 스크립트를 서브프로세스로 띄우는 스크립트([localScript])다.
 * 로컬 스크립트는 임베딩뿐 아니라 세 단계 전부를 `--stage`로 맡는다.
 * 둘 다 비어 있는 것은 오류가 아니다. 테스트에는 실행기가 없는 것이 정상이라 기동을 막지 않고,
 * 실제로 부르려는 순간에 실패한다.
 */
@ConfigurationProperties(prefix = "app.embedding")
data class EmbeddingProperties(
    val functionName: String,
    val localScript: String = "",
) {

    val isConfigured: Boolean
        get() = functionName.isNotBlank()

    val isLocalConfigured: Boolean
        get() = localScript.isNotBlank()
}
