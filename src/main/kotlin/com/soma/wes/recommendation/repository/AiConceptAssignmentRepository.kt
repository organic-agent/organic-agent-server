package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiConceptAssignment
import org.springframework.data.jpa.repository.JpaRepository

interface AiConceptAssignmentRepository : JpaRepository<AiConceptAssignment, Long> {

    fun findAllByJobId(jobId: Long): List<AiConceptAssignment>

    /** 갤러리에서 가장 최근에 배정을 남긴 잡을 찾는 발판. 배정은 잡 단위로 통째 쌓이므로 jobId가 큰 것이 최신이다. */
    fun findFirstByGalleryIdOrderByJobIdDesc(galleryId: Long): AiConceptAssignment?
}
