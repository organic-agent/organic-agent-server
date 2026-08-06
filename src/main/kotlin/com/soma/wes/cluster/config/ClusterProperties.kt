package com.soma.wes.cluster.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 클러스터링 기본값.
 *
 * 임계값은 사용자가 매 요청에 넘기는 손잡이라, 여기 값은 "아직 아무것도 안 만졌을 때 보여줄
 * 입도"에 해당한다. 바뀌면 첫 화면이 통째로 달라지는 정책 값이므로 저장소에 둔다.
 */
@ConfigurationProperties(prefix = "app.cluster")
data class ClusterProperties(
    val defaultThreshold: Double,
)
