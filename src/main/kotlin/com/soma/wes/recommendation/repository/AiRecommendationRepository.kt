package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiRecommendation
import org.springframework.data.jpa.repository.JpaRepository

interface AiRecommendationRepository : JpaRepository<AiRecommendation, Long> {

    /** 추천이 한 라운드라도 있었는지. 잡 모드(draft/refine)를 가른다. */
    fun existsBySelectionId(selectionId: Long): Boolean

    /** 최신 라운드 번호를 얻기 위한 조회. 화면은 항상 최신 라운드만 읽는다. */
    fun findFirstBySelectionIdOrderByRoundDesc(selectionId: Long): AiRecommendation?

    /** 한 라운드 전체. 실행기의 적재 순서와 같은 폴더 → 폴더 안 순위로 정렬한다. */
    fun findAllBySelectionIdAndRoundOrderByFolderIdAscRankAsc(
        selectionId: Long,
        round: Int,
    ): List<AiRecommendation>

    /** 폴더 화면용 — 한 라운드에서 한 폴더의 추천만. */
    fun findAllBySelectionIdAndRoundAndFolderIdOrderByRankAsc(
        selectionId: Long,
        round: Int,
        folderId: Long,
    ): List<AiRecommendation>

    /** 2단계가 이유를 채울 한 라운드의 행. */
    fun findAllBySelectionIdAndRound(selectionId: Long, round: Int): List<AiRecommendation>

    /** 부부가 거절한 추천. 라운드와 무관하게 다음 후보에서 뺀다. */
    fun findAllBySelectionIdAndRejectedAtIsNotNull(selectionId: Long): List<AiRecommendation>
}
