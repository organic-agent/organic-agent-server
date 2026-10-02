package com.soma.wes.folder.dto

/**
 * AI 폴더 계획 — 아직 폴더에 없는 사진이 어디로 가는가. 이미 폴더에 든 사진은 계획에 없다(건드리지 않는다).
 *
 * 처음 분석이면 [newConcepts]만 있고 나머지는 비어 있다.
 */
data class FolderPlanDto(
    /** 기존 세부 폴더 id → 거기에 더 넣을 사진. */
    val merges: Map<Long, List<Long>>,
    /** 기존 컨셉 폴더 id → 그 아래에 새로 만들 세부 폴더. */
    val newDetails: Map<Long, List<DetailFolderPlanDto>>,
    /** 새로 만들 컨셉 폴더와 그 아래 세부 폴더. */
    val newConcepts: List<ConceptFolderPlanDto>,
    /** 기존 폴더에 맞지 않고 새 폴더를 이룰 만큼 모이지도 않아 미분류로 남는 사진. 다음 분석이 다시 본다. */
    val leftUnclassified: List<Long>,
) {

    val isEmpty: Boolean
        get() = merges.isEmpty() && newDetails.isEmpty() && newConcepts.isEmpty()
}
