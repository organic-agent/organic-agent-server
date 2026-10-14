package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.AnalysisJobEvent
import org.springframework.data.jpa.repository.JpaRepository

interface AnalysisJobEventRepository : JpaRepository<AnalysisJobEvent, Long> {

    /** 잡 하나의 이력을 일어난 순서로. */
    fun findAllByJobIdOrderByIdAsc(jobId: Long): List<AnalysisJobEvent>
}
