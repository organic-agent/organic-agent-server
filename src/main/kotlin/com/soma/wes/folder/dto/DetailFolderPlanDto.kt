package com.soma.wes.folder.dto

import com.soma.wes.folder.domain.CutType

/** AI 폴더 계획에서 만들 세부 폴더 하나와 그 안에 넣을 사진. */
data class DetailFolderPlanDto(
    val name: String,
    val cutType: CutType?,
    val needsReview: Boolean,
    val photoIds: List<Long>,
)
