package com.soma.wes.analysis.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** categorize 단계(naming)가 남긴 배정 한 건: 임베딩 그룹 → (컨셉 이름, 세부 이름). 컨셉은 컨셉 폴더(1층), 세부는 세부 폴더(2층)가 된다. */
@Entity
@Table(name = "concept_assignments")
class ConceptAssignment(
    @Column(name = "job_id", nullable = false, updatable = false)
    val jobId: Long,

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "embed_group_id", nullable = false, updatable = false)
    val embedGroupId: Int,

    /** 컨셉 이름('야외 자연') — 컨셉 폴더(1층). 촬영 종류별 고정 목록 안의 값 또는 '기타'. */
    @Column(name = "concept_name", nullable = false, updatable = false, length = 100)
    val conceptName: String,

    /** 세부 이름('해변') — 세부 폴더(2층). VLM의 열린 답 또는 작가 정의 이름. */
    @Column(name = "detail_name", nullable = false, updatable = false, length = 100)
    val detailName: String,

    @Column(name = "confidence", nullable = false, updatable = false)
    val confidence: Float,

    /** vlm(상위 K그룹, VLM이 직접) 또는 nearest(K 밖 소그룹, 임베딩 최근접 상속). */
    @Column(name = "assigned_by", nullable = false, updatable = false, length = 10)
    val assignedBy: String,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** [conceptName]이 '기타'일 때 VLM이 제안한 컨셉 이름. 고정 목록 확장의 근거로만 쓴다. */
    @Column(name = "proposed_concept_name", updatable = false, length = 100)
    val proposedConceptName: String? = null

    /** CLIP zero-shot 다수결이 판정한 컨셉 이름. */
    @Column(name = "clip_concept_name", updatable = false, length = 100)
    val clipConceptName: String? = null
}
