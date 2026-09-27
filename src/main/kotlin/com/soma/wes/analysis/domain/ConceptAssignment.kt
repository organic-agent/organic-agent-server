package com.soma.wes.analysis.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

// [GLOSSARY-1 2026-09-27] AiConceptAssignment → ConceptAssignment (용어집 D4: Ai 접두사 제거). 테이블 이름은 용어 2단계에서 바꾼다.
/**
 * categorize 단계(naming)가 남긴 배정 한 건: 임베딩 그룹 → (컨셉 이름, 세부 이름). 컨셉은 컨셉 폴더(1층), 세부는 세부 폴더(2층)가 된다.
 *
 * 필드 이름은 wes의 폴더 층을 따르고, 컬럼 이름은 AI가 정한 옛 이름 그대로다 — [conceptName]은 `parent_name`,
 * [detailName]은 `concept_name` 컬럼이다(같은 "concept"가 컬럼에선 2층을 가리킨다). 컬럼 이름 변경은 AI와 배포를 맞춰야 해서 따로 한다.
 *
 * 잡 1회의 산출물이라 [jobId]에 매단다 — 같은 갤러리를 다시 돌리면 새 잡 밑에 새 배정이 쌓이고,
 * 폴더 생성은 가장 최근 잡의 배정만 읽는다. 쓰는 쪽은 전부 AI Lambda라 이 서버에서는
 * 읽기 전용 엔티티다. [needsReview] 판정 규칙(CLIP 불일치, 낮은 confidence, 최근접 거리 초과)도
 * 배치가 결정하고 서버는 읽기만 한다.
 */
@Entity
@Table(name = "ai_concept_assignments")
class ConceptAssignment(
    @Column(name = "job_id", nullable = false, updatable = false)
    val jobId: Long,

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "embed_group_id", nullable = false, updatable = false)
    val embedGroupId: Int,

    /** 컨셉 이름('야외 자연') — 컨셉 폴더(1층). 촬영 종류별 고정 목록 안의 값 또는 '기타'. */
    @Column(name = "parent_name", nullable = false, updatable = false, length = 100)
    val conceptName: String,

    /** 세부 이름('해변') — 세부 폴더(2층). VLM의 열린 답 또는 작가 정의 이름. */
    @Column(name = "concept_name", nullable = false, updatable = false, length = 100)
    val detailName: String,

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

    /** [conceptName]이 '기타'일 때 VLM이 제안한 컨셉 이름. 고정 목록 확장의 근거로만 쓴다. */
    @Column(name = "proposed_parent", updatable = false, length = 100)
    val proposedConceptName: String? = null

    /** CLIP zero-shot 다수결이 판정한 컨셉 이름. [conceptName]과 다르면 [needsReview]의 근거다. */
    @Column(name = "clip_parent", updatable = false, length = 100)
    val clipConceptName: String? = null
}
