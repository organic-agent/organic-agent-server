package com.soma.wes.analysis.dto

import com.soma.wes.analysis.domain.AiConceptAssignment

/** 갤러리에서 가장 최근에 배정을 남긴 잡과 그 잡의 컨셉 배정 전부. category 도메인이 폴더를 물질화하는 재료다. */
data class LatestAssignmentsDto(
    val jobId: Long,
    val assignments: List<AiConceptAssignment>,
)
