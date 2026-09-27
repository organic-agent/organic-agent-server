package com.soma.wes.folder.dto

/** AI 폴더 계획(AiFolderPlanner)에 넣을 사진 한 장의 분석 값 — 임베딩 그룹·피사체·연사. */
data class PhotoAnalysisGroupingDto(
    val photoId: Long,
    val embedGroupId: Int?,
    val subjects: String?,
    val burstId: Int?,
)
