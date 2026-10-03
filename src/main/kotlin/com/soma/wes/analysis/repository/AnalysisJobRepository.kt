package com.soma.wes.analysis.repository

import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import java.time.ZonedDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * 분석 잡 저장소. 상태 전이는 전부 **조건부 UPDATE 한 문장**이다 — 기대한 상태일 때만 옮기고 영향 행 수(0|1)를 돌려준다.
 * 스윕 둘이 같은 잡을 봐도 1을 받은 쪽만 다음 일(EVENT·알림)을 한다. 문장마다 자기 트랜잭션이라 호출자는 트랜잭션을 열지 않는다.
 * `version`·`updated_at`을 함께 올려 관리자 API의 `expectedVersion` 검사가 이 변경도 보게 한다.
 * `error`는 categorize Lambda도 쓰는 컬럼이라 잡을 FAILED로 닫을 때만 쓴다.
 */
interface AnalysisJobRepository : JpaRepository<AnalysisJob, Long> {

    /** 갤러리의 가장 최근 잡. 이력이 쌓이므로 id가 큰 것이 최근이다. */
    fun findFirstByGalleryIdOrderByIdDesc(galleryId: Long): AnalysisJob?

    fun existsByGalleryIdAndStatusIn(galleryId: Long, statuses: Collection<AnalysisStatus>): Boolean

    /** 이 잡([id])보다 앞선 같은 갤러리의 [status] 잡 중 가장 최근 것. 실패 알림이 "이번 잡의 몫"을 세는 기준 시각(직전 DONE)을 준다. */
    fun findFirstByGalleryIdAndIdLessThanAndStatusOrderByIdDesc(galleryId: Long, id: Long, status: AnalysisStatus): AnalysisJob?

    /** 지금 돌고 있는 잡 수. 하트비트가 찍는 값이다. */
    fun countByStatusIn(statuses: Collection<AnalysisStatus>): Long

    /** 파이프라인 단계의 입력 — 한 상태의 잡 전부. 오래된 것부터 본다. */
    fun findAllByStatusOrderByIdAsc(status: AnalysisStatus): List<AnalysisJob>

    /** ANALYZING → CATEGORIZING. categorize 전송 시각과 시도 수(1)를 함께 남긴다. 0이면 다른 스윕이 먼저 옮겼다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.status = :categorizing, j.dispatchedAt = :now, j.categorizingAt = :now, j.attempts = j.attempts + 1,
            j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :analyzing
        """,
    )
    fun startCategorizing(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("analyzing") analyzing: AnalysisStatus = AnalysisStatus.ANALYZING,
        @Param("categorizing") categorizing: AnalysisStatus = AnalysisStatus.CATEGORIZING,
    ): Int

    /**
     * 결과가 늦은 categorize를 다시 보낼 자리를 잡는다 — 전송 시각이 비었거나 [dispatchedBefore]보다 오래됐고 시도가 남았을 때만.
     * 0이면 다른 스윕이 먼저 다시 보냈다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.dispatchedAt = :now, j.attempts = j.attempts + 1, j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :categorizing AND j.attempts < :maxAttempts
          AND (j.dispatchedAt IS NULL OR j.dispatchedAt <= :dispatchedBefore)
        """,
    )
    fun redispatchCategorize(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("dispatchedBefore") dispatchedBefore: ZonedDateTime,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("categorizing") categorizing: AnalysisStatus = AnalysisStatus.CATEGORIZING,
    ): Int

    /** 호출 자체가 실패했다 — 전송 시각을 지워 다음 회차가 타임아웃을 기다리지 않고 다시 보내게 한다. 시도 수는 그대로다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.dispatchedAt = NULL, j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :categorizing
        """,
    )
    fun clearDispatchedAt(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("categorizing") categorizing: AnalysisStatus = AnalysisStatus.CATEGORIZING,
    ): Int

    /** CATEGORIZING → DONE. 0이면 다른 스윕이 먼저 닫았다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.status = :done, j.finishedAt = :now, j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :categorizing
        """,
    )
    fun finish(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("categorizing") categorizing: AnalysisStatus = AnalysisStatus.CATEGORIZING,
        @Param("done") done: AnalysisStatus = AnalysisStatus.DONE,
    ): Int

    /**
     * ANALYZING 잡의 진행 감시 값을 옮긴다 — 진행 값이 바뀌었을 때만 부른다. 그래서 [AnalysisJob.progressAt]이 곧
     * "마지막으로 진행이 있던 시각"이다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.progressCount = :progressCount, j.progressAt = :now, j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :analyzing
        """,
    )
    fun recordProgress(
        @Param("id") id: Long,
        @Param("progressCount") progressCount: Int,
        @Param("now") now: ZonedDateTime,
        @Param("analyzing") analyzing: AnalysisStatus = AnalysisStatus.ANALYZING,
    ): Int

    /** 폴더 만들기가 예상 밖 예외로 실패했다 — 횟수를 하나 올린다. 호출자가 올린 뒤의 값을 상한과 견준다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.materializeAttempts = j.materializeAttempts + 1, j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status = :categorizing
        """,
    )
    fun countMaterializeFailure(
        @Param("id") id: Long,
        @Param("now") now: ZonedDateTime,
        @Param("categorizing") categorizing: AnalysisStatus = AnalysisStatus.CATEGORIZING,
    ): Int

    /**
     * 살아 있는 잡(ANALYZING·CATEGORIZING) → FAILED. [error]는 [AnalysisJob.trimError]로 자른 내부 문장이고 DB 에만 남는다.
     * 사용자와 운영 알림이 보는 것은 [errorCode]다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AnalysisJob j
        SET j.status = :failed, j.error = :error, j.errorCode = :errorCode, j.finishedAt = :now,
            j.version = j.version + 1, j.updatedAt = :now
        WHERE j.id = :id AND j.status IN :active
        """,
    )
    fun fail(
        @Param("id") id: Long,
        @Param("error") error: String,
        @Param("errorCode") errorCode: AnalysisFailureCode,
        @Param("now") now: ZonedDateTime,
        @Param("active") active: Collection<AnalysisStatus> = AnalysisStatus.ACTIVE,
        @Param("failed") failed: AnalysisStatus = AnalysisStatus.FAILED,
    ): Int
}
