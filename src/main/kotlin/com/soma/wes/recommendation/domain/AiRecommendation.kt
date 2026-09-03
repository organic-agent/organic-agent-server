package com.soma.wes.recommendation.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

/**
 * AI가 한 셀렉에 제시한 사진 한 장. 행을 만드는 것은 AI 워커다 — 폴더마다 점수 상위 n장을
 * INSERT하고(이때 [reason]은 null), LLM 이유 문장을 뒤이어 UPDATE 한다(2단계). 이 서버는 읽기만
 * 하므로 전부 `val`이다.
 *
 * 라운드마다 통째로 쌓이고 화면은 최신 라운드만 읽는다 — refine이 이전 라운드를 고치는 대신 새
 * 라운드를 다시 적기 때문에 같은 사진이 여러 라운드에 나올 수 있다(`round`가 유니크에 드는 이유).
 * `score_breakdown`(jsonb)과 반응 컬럼(accepted/rejected/unselected/accept_mode)은 이 서버가 아직
 * 읽지 않아 매핑하지 않았다 — 반응 API가 생길 때 함께 매핑한다.
 */
@Entity
@Table(
    name = "ai_recommendations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_ai_recommendations_selection_round_photo",
            columnNames = ["selection_id", "round", "photo_id"],
        ),
    ],
)
class AiRecommendation(

    @Column(name = "selection_id", nullable = false, updatable = false)
    val selectionId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

    @Column(name = "round", nullable = false, updatable = false)
    val round: Int,

    /** 폴더 안 순위. 1이 그 폴더의 대표다. */
    @Column(name = "rank", nullable = false, updatable = false)
    val rank: Int,

    @Column(name = "presented_at", nullable = false, updatable = false)
    val presentedAt: ZonedDateTime,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /**
     * 추천 당시의 자식 폴더. 세트에 안 들어간 사진(미분류 가상 폴더)은 null. 재현용이다 —
     * 현재 세부폴더 id를 재현용으로 남기며, 화면은 사진의 현재 카테고리 배정과 구분해 다룬다.
     */
    @Column(name = "folder_id", updatable = false)
    val folderId: Long? = null

    /** 이유 문장. 2단계라 처음에는 null이고, 프론트는 null이면 "이유 준비 중"을 그린다. */
    @Column(name = "reason")
    val reason: String? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 AiRecommendation 이다")
}
