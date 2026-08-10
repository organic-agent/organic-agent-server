package com.soma.wes.studio.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 운영자 hard delete의 배포와 활성화를 분리하는 safety gate.
 *
 * 코드·스키마가 배포돼도 Embedding writer 권한·이미지와 인프라 운영 절차가
 * 준비되기 전에는 실행하면 안 된다. 설정을 생략한 모든 환경에서도 비활성이다.
 */
@ConfigurationProperties(prefix = "app.studio-hard-deletion")
data class StudioHardDeletionProperties(
    val enabled: Boolean = false,
)
