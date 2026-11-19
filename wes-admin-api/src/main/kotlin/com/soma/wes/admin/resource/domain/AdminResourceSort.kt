package com.soma.wes.admin.resource.domain

/**
 * 통합 검색 목록의 정렬 기준. 둘 다 최근 것이 먼저다.
 *
 * - [UPDATED]: 마지막 수정 시각 순 — 방금 손댄 리소스를 찾을 때. 수정이 잦은 리소스가 위로 올라온다.
 * - [CREATED]: 생성 시각 순 — 새로 생긴 갤러리·가입한 유저를 볼 때.
 */
enum class AdminResourceSort(val orderBy: String) {
    UPDATED("updated_at DESC NULLS LAST, resource_type, id DESC"),
    CREATED("created_at DESC NULLS LAST, resource_type, id DESC"),
}
