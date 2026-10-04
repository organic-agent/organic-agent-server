package com.soma.wes.analysis.dto

/** 임베딩 그룹 하나에 붙은 컨셉 배정 — 컨셉 이름·세부 이름. */
data class ConceptAssignmentDto(
    val embedGroupId: Int,
    val conceptName: String,
    val detailName: String,
)
