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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * AI가 한 셀렉에 제시한 사진 한 장. 실행기가 폴더마다 점수 상위 n장을 INSERT한다.
 *
 * 추천은 **사진에 붙는다**. 화면은 사진마다 가장 최근 라운드의 행을 읽으므로(라운드 전체 교체가 아니다),
 * 사진을 다른 폴더로 옮겨도 표시는 따라간다. 잡이 돌면 그 잡의 범위(폴더 하나 또는 전체)에 든 사진의
 * 기존 행을 지우고 새 라운드로 다시 적는다 — 거절 행([rejectedAt])은 지우지 않고 남겨 다음 계산이 뺀다.
 * accepted/unselected/accept_mode는 반응 API가 생길 때 매핑한다.
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

    /**
     * 추천 당시의 세부폴더(재현용). 세트에 안 들어간 사진(미분류 가상 폴더)은 null. 화면 응답의
     * folderId는 이 값이 아니라 사진의 **현재** 배정이다 — 표시는 사진을 따라간다.
     */
    @Column(name = "folder_id", updatable = false)
    val folderId: Long? = null,

    /** 점수·순위·폴더 몫 등 이 추천이 만들어진 근거. 키는 AI repo v3 `score_breakdown`과 같다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_breakdown", nullable = false, columnDefinition = "jsonb")
    val scoreBreakdown: Map<String, Any?> = emptyMap(),

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 부부가 이 추천을 거절한 시각. 다음 라운드는 거절된 사진을 후보에서 뺀다. */
    @Column(name = "rejected_at")
    val rejectedAt: ZonedDateTime? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 AiRecommendation 이다")
}
