package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiRecommendation
import java.time.ZonedDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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

    /** 셀렉의 추천 전부(모든 라운드, 갈린 행 포함). 화면은 여기서 사진마다 가장 최근 것을 고르고 갈린·거절 행을 뺀다. */
    fun findAllBySelectionId(selectionId: Long): List<AiRecommendation>

    /**
     * 컨텍스트를 비우지 않는다 — 같은 트랜잭션에서 먼저 읽어 둔 잡 엔티티가 분리되면 라운드 번호 갱신이 사라진다.
     * 이 시점에 컨텍스트에 추천 엔티티는 없다. JPQL 벌크라 `version`·`updated_at`을 손으로 올린다(RP-11).
     *
     * 잡 범위에 든 사진의 지금 보이는 추천을 "갈림"으로 표시한다 — 새 라운드를 적기 전의 리셋. 지우지 않고 남겨 노출 기록이 된다.
     * 거절 행과 이미 갈린 행은 건드리지 않는다.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(
        """
        UPDATE AiRecommendation r
        SET r.replacedAt = :at, r.updatedAt = :at, r.version = r.version + 1
        WHERE r.selectionId = :selectionId AND r.photoId IN :photoIds AND r.rejectedAt IS NULL AND r.replacedAt IS NULL
        """,
    )
    fun markReplacedBySelectionIdAndPhotoIdIn(
        @Param("selectionId") selectionId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
        @Param("at") at: ZonedDateTime,
    ): Int
}
