package com.soma.wes.folder.dto

// [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner 안에 중첩돼 있던 data class를 dto 루트로 옮겼다 (AiFolderPlanner.ConceptPlan → ConceptPlanDto).
/** 만들 컨셉 폴더 하나와 그 세부 폴더 계획. [details]는 화면 순서(기타 맨 뒤, 사진 많은 순)로 이미 정렬돼 있다. */
data class ConceptPlanDto(
    val name: String,
    val details: List<DetailPlanDto>,
)
