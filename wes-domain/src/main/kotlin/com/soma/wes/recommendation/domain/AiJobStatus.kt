package com.soma.wes.recommendation.domain

/**
 * AI 잡의 생애. 이 서버는 [PENDING]만 만들고, 나머지 전이는 잡을 집어간 AI 쪽(분석 배치·Lambda)이
 * DB에서 직접 한다.
 */
enum class AiJobStatus {

    /** 이 서버가 넣어 둔 상태. 아직 아무도 집어가지 않았다. */
    PENDING,

    RUNNING,

    DONE,

    FAILED,
    ;

    val isActive: Boolean
        get() = this == PENDING || this == RUNNING

    companion object {

        /** 갤러리·셀렉당 하나만 허용되는 상태들. `uk_ai_*_jobs_active` 부분 유니크 인덱스와 같은 집합이다. */
        val ACTIVE: Set<AiJobStatus> = setOf(PENDING, RUNNING)
    }
}
