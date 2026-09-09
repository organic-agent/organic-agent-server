package com.soma.wes.analysis.dto

import java.time.ZonedDateTime

/** GPU score 워커 인스턴스 하나의 지금 상태. [ScoreWorkerPool.snapshot]이 돌려준다. */
data class ScoreWorkerDto(
    val instanceId: String,
    val state: ScoreWorkerStateDto,
    /** 마지막으로 켠 시각. 켜진 적 없으면 null. 기동 유예([com.soma.wes.analysis.config.AnalysisProperties.Gpu.startGrace])의 기준이다. */
    val launchedAt: ZonedDateTime?,
) {

    /** 켜졌거나 켜지는 중 — 곧 집기를 시작할 것이라 다시 켜지 않는다. */
    val isUp: Boolean
        get() = state == ScoreWorkerStateDto.RUNNING || state == ScoreWorkerStateDto.PENDING
}
