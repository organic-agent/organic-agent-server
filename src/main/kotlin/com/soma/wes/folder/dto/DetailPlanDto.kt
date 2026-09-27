package com.soma.wes.folder.dto

import com.soma.wes.folder.domain.CutType

// [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner 안에 중첩돼 있던 data class를 dto 루트로 옮겼다 (AiFolderPlanner.DetailPlan → DetailPlanDto).
/** 만들 세부 폴더 하나와 그 안에 넣을 사진. [cutType]은 과반일 때만 붙고, 그룹 중 하나라도 검토 대상이면 [needsReview]다. */
data class DetailPlanDto(
    val name: String,
    val cutType: CutType?,
    val needsReview: Boolean,
    val photoIds: List<Long>,
)
