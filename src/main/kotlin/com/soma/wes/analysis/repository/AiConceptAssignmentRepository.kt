package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.AiConceptAssignment
import org.springframework.data.jpa.repository.JpaRepository

interface AiConceptAssignmentRepository : JpaRepository<AiConceptAssignment, Long> {

    fun findAllByJobId(jobId: Long): List<AiConceptAssignment>

    /** categorize가 이 잡의 배정을 남겼는가 — 잡이 CATEGORIZING을 닫는 관측 조건이다. */
    fun existsByJobId(jobId: Long): Boolean

    /** 갤러리에서 가장 최근에 배정을 남긴 잡을 찾는 발판. 배정은 잡 단위로 통째 쌓이므로 jobId가 큰 것이 최신이다. */
    fun findFirstByGalleryIdOrderByJobIdDesc(galleryId: Long): AiConceptAssignment?
}
