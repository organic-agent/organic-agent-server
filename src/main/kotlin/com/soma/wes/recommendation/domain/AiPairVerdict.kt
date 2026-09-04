package com.soma.wes.recommendation.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 비교샷 판정 한 건. 셀렉 안에서 사진 쌍당 한 행이고 순서는 무관하다 — 유니크는 DB의 식 인덱스
 * `(selection_id, LEAST(photo_a, photo_b), GREATEST(photo_a, photo_b))`가 지킨다.
 *
 * 같은 쌍을 다시 판정하는 경우는 [modelVersion](모델 id + 프롬프트 세대)이 바뀌었을 때뿐이며, 그때는
 * 새 행이 아니라 이 행을 [replaceWith]로 덮는다. 사람의 답(pair_comparison_events)과는 분리 저장 —
 * 누가 골랐는지가 흐려지면 안 된다.
 */
@Entity
@Table(name = "ai_pair_verdicts")
class AiPairVerdict(

    @Column(name = "selection_id", nullable = false, updatable = false)
    val selectionId: Long,

    @Column(name = "photo_a", nullable = false, updatable = false)
    val photoA: Long,

    @Column(name = "photo_b", nullable = false, updatable = false)
    val photoB: Long,

    chosenPhotoId: Long,
    confidence: String,
    reason: String,
    facts: Map<String, Any?>,
    modelVersion: String,
    source: String,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "chosen_photo_id", nullable = false)
    var chosenPhotoId: Long = chosenPhotoId
        protected set

    /** clear | slight */
    @Column(name = "confidence", nullable = false, length = 10)
    var confidence: String = confidence
        protected set

    @Column(name = "reason", nullable = false)
    var reason: String = reason
        protected set

    /** 판정에 준 재료(수치)의 저장본. 나중에 "왜 이렇게 골랐나"를 되짚는 근거다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facts", nullable = false, columnDefinition = "jsonb")
    var facts: Map<String, Any?> = facts
        protected set

    @Column(name = "model_version", nullable = false, length = 50)
    var modelVersion: String = modelVersion
        protected set

    /** llm | template */
    @Column(name = "source", nullable = false, length = 10)
    var source: String = source
        protected set

    fun replaceWith(
        chosenPhotoId: Long,
        confidence: String,
        reason: String,
        facts: Map<String, Any?>,
        modelVersion: String,
        source: String,
    ) {
        this.chosenPhotoId = chosenPhotoId
        this.confidence = confidence
        this.reason = reason
        this.facts = facts
        this.modelVersion = modelVersion
        this.source = source
    }
}
