package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.ConceptAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ConceptAssignmentRepository : JpaRepository<ConceptAssignment, Long> {

    /** categorize가 이 잡의 배정을 남겼는가 — 잡이 CATEGORIZING을 닫는 관측 조건이다. */
    fun existsByJobId(jobId: Long): Boolean

    /**
     * 갤러리에서 가장 최근에 배정을 남긴 잡의 배정 전부. 배정은 잡마다 통째로 쌓이고 지워지지 않으므로(재분석하면 잡 수만큼 묶음이 남는다)
     * 갤러리로만 읽으면 여러 잡이 섞인다 — jobId가 가장 큰 잡 하나로 좁힌다. 배정이 없으면 빈 목록.
     */
    @Query(
        """
        SELECT a FROM ConceptAssignment a
        WHERE a.galleryId = :galleryId
          AND a.jobId = (SELECT MAX(b.jobId) FROM ConceptAssignment b WHERE b.galleryId = :galleryId)
        """,
    )
    fun findAllOfLatestJobByGalleryId(@Param("galleryId") galleryId: Long): List<ConceptAssignment>
}
