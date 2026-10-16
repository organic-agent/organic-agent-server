package com.soma.wes.agent.domain

/**
 * 도우미가 거는 사진 조건. 비어 있는 칸은 조건이 없다는 뜻이다.
 *
 * 화면의 필터 칩과 같은 모양이라, 도우미가 건 조건을 사용자가 칩으로 고치거나 끄고 다시 보낼 수 있다.
 * 컨셉과 세부 폴더를 함께 주면 둘 중 하나에 들면 통과한다 — "야외 폴더랑 케이크 폴더"처럼 말하기 때문이다.
 */
data class PhotoFilter(
    val conceptFolderIds: List<Long> = emptyList(),
    val detailFolderIds: List<Long> = emptyList(),
    val subjects: List<AgentSubject> = emptyList(),
    /** true면 고른 사진만, false면 아직 안 고른 사진만. */
    val selected: Boolean? = null,
    val orientation: PhotoOrientation? = null,
    /** 비슷한 연사 묶음마다 대표 한 장(`burst_rank = 0`)만. */
    val burstRepresentativeOnly: Boolean = false,
) {
    /** 프로퍼티가 아니라 함수다 — 응답 JSON에 칸으로 새어 나가지 않게. */
    fun hasFolder(): Boolean = conceptFolderIds.isNotEmpty() || detailFolderIds.isNotEmpty()
}
