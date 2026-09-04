package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiPairVerdict
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AiPairVerdictRepository : JpaRepository<AiPairVerdict, Long> {

    /** 순서 무관 조회 — 유니크 식 인덱스와 같은 식이라 인덱스를 탄다. */
    @Query(
        value = """
            SELECT * FROM ai_pair_verdicts
            WHERE selection_id = :selectionId
              AND LEAST(photo_a, photo_b) = LEAST(:photoA, :photoB)
              AND GREATEST(photo_a, photo_b) = GREATEST(:photoA, :photoB)
        """,
        nativeQuery = true,
    )
    fun findByPair(
        @Param("selectionId") selectionId: Long,
        @Param("photoA") photoA: Long,
        @Param("photoB") photoB: Long,
    ): AiPairVerdict?
}
