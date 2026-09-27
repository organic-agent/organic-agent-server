package com.soma.wes.folder.dto

// [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner 안에 중첩돼 있던 data class를 dto 루트로 옮겼다 (AiFolderPlanner.GroupAssignment → GroupAssignmentDto).
/** 임베딩 그룹 하나에 AI가 붙인 컨셉·세부 폴더 이름. 폴더 계획의 입력이다 — 이 쪽지가 없는 그룹의 사진은 "기타"로 간다. */
data class GroupAssignmentDto(
    val embedGroupId: Int,
    val conceptName: String,
    val detailName: String,
    val needsReview: Boolean,
)
