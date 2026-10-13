package com.soma.wes.recommendation.repository

import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiSelectionJob
import java.time.ZonedDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AiSelectionJobRepository : JpaRepository<AiSelectionJob, Long> {

    /** 셀렉의 가장 최근 잡. 이력이 쌓이므로 id가 큰 것이 최근이다. */
    fun findFirstBySelectionIdOrderByIdDesc(selectionId: Long): AiSelectionJob?

    fun existsBySelectionIdAndStatusIn(selectionId: Long, statuses: Collection<AiJobStatus>): Boolean

    fun findAllByStatusOrderByIdAsc(status: AiJobStatus): List<AiSelectionJob>

    /**
     * PENDING → RUNNING. 이미 누가 집었거나 없는 잡이면 0 — 실행기 둘(즉시 실행·스윕)이 같은 잡을 돌리지 않게
     * 상태 조건을 UPDATE 안에 둔다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AiSelectionJob j
        SET j.status = :running, j.startedAt = :now, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :pending
        """,
    )
    fun claim(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("running") running: AiJobStatus = AiJobStatus.RUNNING,
        @Param("pending") pending: AiJobStatus = AiJobStatus.PENDING,
    ): Int
}
