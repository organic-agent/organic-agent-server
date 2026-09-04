package com.soma.wes.analysis.domain

/**
 * 분석 잡과 그 단계의 생애. 잡([AnalysisJob.status])과 단계([AnalysisJob.stageStatus])가 같은 값 집합을 쓴다.
 *
 * `recommendation`의 `AiJobStatus`와 값이 같지만 일부러 따로 둔다 — 두 도메인이 enum 하나를 공유하면 한쪽의
 * 전이 규칙 변경이 다른 쪽을 흔든다.
 */
enum class AnalysisStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    ;

    val isActive: Boolean
        get() = this == PENDING || this == RUNNING

    companion object {
        /** 갤러리당 하나만 허용되는 상태들. `uk_ai_analysis_jobs_active` 부분 유니크 인덱스와 같은 집합이다. */
        val ACTIVE: Set<AnalysisStatus> = setOf(PENDING, RUNNING)
    }
}
