package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface AnalysisJobRepository : JpaRepository<AnalysisJob, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): AnalysisJob?

    @Query("""
        select j.id from AnalysisJob j
        where j.status = 'DONE' and j.completionNotifiedAt is null
          and exists (select g.id from Gallery g where g.id = j.galleryId)
        order by j.id
    """)
    fun findAwaitingCompletionNotification(pageable: Pageable): List<Long>

    /** 갤러리의 가장 최근 잡. 이력이 쌓이므로 id가 큰 것이 최근이다. */
    fun findFirstByGalleryIdOrderByIdDesc(galleryId: Long): AnalysisJob?

    fun existsByGalleryIdAndStatusIn(galleryId: Long, statuses: Collection<AnalysisStatus>): Boolean

    fun existsByGalleryIdAndModeAndStatus(galleryId: Long, mode: AnalysisMode, status: AnalysisStatus): Boolean

    /** 스윕 대상 — 살아 있는 잡 전부. 오래된 것부터 본다. */
    fun findAllByStatusInOrderByIdAsc(statuses: Collection<AnalysisStatus>): List<AnalysisJob>
}
