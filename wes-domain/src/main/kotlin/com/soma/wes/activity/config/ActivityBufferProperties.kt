package com.soma.wes.activity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 활동 시각 모아 쓰기(#286 (f)). 켜면 [com.soma.wes.activity.service.ActivityRecorder]가 활동 시각을 요청 트랜잭션에서
 * 바로 쓰지 않고 메모장에 남기며, [com.soma.wes.activity.support.ActivityFlushScheduler]가 [flushInterval]마다 모아 쓴다.
 *
 * 한 스튜디오의 여러 갤러리에서 동시에 쓰기가 일어나면 모든 쓰기가 `workspace_activity`의 작업공간 한 행을 기다렸다
 * (R-3-a4: 활동 upsert 평균 1초·최대 140초). wes-api만 켠다 — 관리자 앱과 테스트는 꺼져 있어 지금처럼 바로 쓴다.
 */
@ConfigurationProperties(prefix = "app.activity.buffer")
data class ActivityBufferProperties(
    val enabled: Boolean = false,
    /** 모아 둔 시각을 쓰는 주기. 활동 시각이 늦게 보이는 최대 시간이고, 앱이 죽으면 잃을 수 있는 구간이다. */
    val flushInterval: Duration = Duration.ofSeconds(5),
)
