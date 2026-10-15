package com.soma.wes.global.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 주기 작업 리더 임대 손잡이(#313 ADR-0002). 서버가 여러 대일 때 `@Scheduled` 작업을 한 대만 돌리게 한다.
 *
 * 리더가 죽으면 [ttl] 뒤 다른 서버가 이어받는다 — 그동안 주기 작업(분석 스윕 등)은 멈췄다 이어진다. [renewInterval]은 [ttl]의 ⅓ 이하로
 * 둬야 갱신 한두 번이 늦어도 임대를 놓치지 않는다.
 */
@ConfigurationProperties(prefix = "app.cluster.leader")
data class ClusterLeaderProperties(
    val enabled: Boolean = false,
    val key: String = "wes:scheduler-leader",
    val ttl: Duration = Duration.ofSeconds(30),
    val renewInterval: Duration = Duration.ofSeconds(10),
)
