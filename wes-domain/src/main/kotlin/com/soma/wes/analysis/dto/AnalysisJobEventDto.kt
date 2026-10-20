package com.soma.wes.analysis.dto

import com.soma.wes.analysis.domain.AnalysisJobEventType
import java.time.ZonedDateTime

/** 남길 분석 잡 이력 한 줄. 부른 쪽 트랜잭션이 커밋된 뒤 이력 스레드가 받아 쓴다. 시각은 부른 때의 것이다. */
data class AnalysisJobEventDto(
    val jobId: Long,
    val galleryId: Long,
    val type: AnalysisJobEventType,
    val detail: Map<String, Any?>?,
    val createdAt: ZonedDateTime,
)
