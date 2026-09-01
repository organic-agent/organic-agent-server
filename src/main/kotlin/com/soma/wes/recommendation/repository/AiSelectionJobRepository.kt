package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiSelectionJob
import org.springframework.data.jpa.repository.JpaRepository

interface AiSelectionJobRepository : JpaRepository<AiSelectionJob, Long> {

    /** 셀렉의 가장 최근 잡. 이력이 쌓이므로 id가 큰 것이 최근이다. */
    fun findFirstBySelectionIdOrderByIdDesc(selectionId: Long): AiSelectionJob?

    fun existsBySelectionIdAndStatusIn(selectionId: Long, statuses: Collection<AiJobStatus>): Boolean
}
