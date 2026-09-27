package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.ConceptAssignment
import org.springframework.data.jpa.repository.JpaRepository

// [GLOSSARY-1 2026-09-27] AiConceptAssignmentRepository → ConceptAssignmentRepository (용어집 D4)
interface ConceptAssignmentRepository : JpaRepository<ConceptAssignment, Long> {

    fun findAllByJobId(jobId: Long): List<ConceptAssignment>

    /** categorize가 이 잡의 배정을 남겼는가 — 잡이 CATEGORIZING을 닫는 관측 조건이다. */
    fun existsByJobId(jobId: Long): Boolean

    /** 갤러리에서 가장 최근에 배정을 남긴 잡을 찾는 발판. 배정은 잡 단위로 통째 쌓이므로 jobId가 큰 것이 최신이다. */
    fun findFirstByGalleryIdOrderByJobIdDesc(galleryId: Long): ConceptAssignment?
}
