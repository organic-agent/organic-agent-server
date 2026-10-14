package com.soma.wes.folder.dto

/** AI 폴더 계획에서 만들 컨셉 폴더 하나와 그 아래 세부 폴더 계획. */
data class ConceptFolderPlanDto(
    val name: String,
    val details: List<DetailFolderPlanDto>,
)
