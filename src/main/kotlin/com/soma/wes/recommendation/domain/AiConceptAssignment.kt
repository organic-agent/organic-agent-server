package com.soma.wes.recommendation.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * naming 잡이 남긴 컨셉 배정 한 건: 임베딩 그룹 → (큰 분류, 컨셉 이름).
 *
 * 잡 1회의 산출물이라 [jobId]에 매단다 — 같은 갤러리를 다시 돌리면 새 잡 밑에 새 배정이 쌓이고,
 * 폴더 생성은 최신 DONE naming 잡의 배정만 읽는다. 쓰는 쪽은 전부 AI 배치라 이 서버에서는
 * 읽기 전용 엔티티다. [needsReview] 판정 규칙(CLIP 불일치, 낮은 confidence, 최근접 거리 초과)도
 * 배치가 결정하고 서버는 읽기만 한다.
 */
@Entity
@Table(name = "ai_concept_assignments")
class AiConceptAssignment(

    @Column(name = "job_id", nullable = false, updatable = false)
    val jobId: Long,

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "embed_group_id", nullable = false, updatable = false)
    val embedGroupId: Int,

    /** 큰 분류('야외 자연'). 촬영 종류별 고정 목록 안의 값 또는 '기타'. */
    @Column(name = "parent_name", nullable = false, updatable = false, length = 100)
    val parentName: String,

    /** 컨셉 이름('해변'). VLM의 열린 답 또는 작가 정의 컨셉. */
    @Column(name = "concept_name", nullable = false, updatable = false, length = 100)
    val conceptName: String,

    @Column(name = "confidence", nullable = false, updatable = false)
    val confidence: Float,

    /** vlm(상위 K그룹, VLM이 직접) 또는 nearest(K 밖 소그룹, 임베딩 최근접 상속). */
    @Column(name = "assigned_by", nullable = false, updatable = false, length = 10)
    val assignedBy: String,

    @Column(name = "needs_review", nullable = false, updatable = false)
    val needsReview: Boolean = false,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** [parentName]이 '기타'일 때 VLM이 제안한 이름. 고정 목록 확장의 근거로만 쓴다. */
    @Column(name = "proposed_parent", updatable = false, length = 100)
    val proposedParent: String? = null

    /** CLIP zero-shot 다수결이 판정한 큰 분류. [parentName]과 다르면 [needsReview]의 근거다. */
    @Column(name = "clip_parent", updatable = false, length = 100)
    val clipParent: String? = null
}
