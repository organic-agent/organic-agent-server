package com.soma.wes.agent.dto

import com.soma.wes.agent.domain.PhotoFilter

/**
 * 판단기에게 질문과 함께 주는 갤러리 사정. 폴더 이름만으로는 "두 번째로 찍은 것"·"야외"를 못 풀어서
 * 촬영 순서와 시간대를 같이 준다 — 폴더 정렬 순서와 촬영 순서는 다르다(화이트 → 블랙수트 → 다크 → 야외처럼).
 *
 * 사진 id는 주지 않는다. 판단기는 조건만 정하고, 어떤 사진이 걸리는지는 서비스가 센다.
 */
data class AgentContextDto(
    val concepts: List<Concept>,
    val selectedCount: Int,
    val maxSelectablePhotoCount: Int?,
    val screen: Screen?,
) {

    data class Concept(
        val id: Long,
        val name: String,
        /** 1부터, 가장 먼저 찍은 컨셉이 1. 찍은 시각이 없는 컨셉은 뒤로 간다. */
        val shotOrder: Int,
        /** "14:05~14:40". 찍은 시각이 없으면 null. */
        val timeRange: String?,
        val photoCount: Int,
        val details: List<Detail>,
    )

    data class Detail(
        val id: Long,
        val name: String,
        val photoCount: Int,
    )

    /** 사용자가 지금 보고 있는 화면. "지금 보이는 거"를 이것으로 푼다. */
    data class Screen(
        val conceptFolderId: Long?,
        val detailFolderId: Long?,
        val filter: PhotoFilter?,
    )
}
