package com.soma.wes.agent.fixture

import com.soma.wes.gallery.fixture.OpenGallery

/**
 * 도우미 테스트의 갤러리. 컨셉 둘(화이트·야외), 세부 셋(화이트A·정원·노을), 사진 아홉 장.
 *
 * | 사진 | 폴더 | 피사체 | 연사(묶음/순위) | 방향 | 찍은 시각 |
 * |---|---|---|---|---|---|
 * | p[0] | 화이트A | bride | 1/0 | 세로 | 10:00 |
 * | p[1] | 화이트A | bride | 1/1 | 세로 | 10:01 |
 * | p[2] | 화이트A | groom | 3/0 | 가로 | 10:02 |
 * | p[3] | 화이트A | couple | 4/0 | 세로 | 10:03 |
 * | p[4] | 정원 | groom | 5/0 | 세로 | 15:00 |
 * | p[5] | 정원 | couple | 2/0 | 가로 | 15:01 |
 * | p[6] | 정원 | couple | 2/1 | 가로 | 15:02 |
 * | p[7] | 노을 | couple | 6/0 | 가로 | 18:00 |
 * | p[8] | 미분류 | group | 7/0 | 세로 | 없음 |
 *
 * 혼자인 사진도 자기 연사 묶음의 대표(순위 0)다 — categorize가 그렇게 매긴다.
 * 폴더 정렬은 야외가 앞이지만 촬영은 화이트가 먼저다 — 촬영 순서가 정렬 순서와 다른 갤러리다.
 */
data class AgentScene(
    val gallery: OpenGallery,
    val whiteConceptId: Long,
    val whiteDetailId: Long,
    val outdoorConceptId: Long,
    val gardenDetailId: Long,
    val sunsetDetailId: Long,
    val photoIds: List<Long>,
) {
    val galleryId: Long
        get() = gallery.galleryId

    val memberId: Long
        get() = gallery.member.requiredId
}
