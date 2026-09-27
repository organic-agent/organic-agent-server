package com.soma.wes.analysis.dto

/** 갤러리에서 가장 최근에 컨셉 배정을 남긴 분석 잡과 그 배정 전부 — AI 폴더 물질화의 재료. */
data class LatestConceptAssignmentsDto(
    val analysisJobId: Long,
    val assignments: List<ConceptAssignmentDto>,
)
