package com.soma.wes.analysis.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime
import org.hibernate.annotations.DynamicUpdate

/**
 * 갤러리 한 번의 "폴더 만들기" — ANALYZING → CATEGORIZING → DONE | FAILED. 행이 곧 큐 항목이다.
 *
 * 사진별 진행(임베딩·점수·백분위)은 잡이 아니라 `photo_analysis` 행이 말한다. 잡은 그것을 관측해 점수가 다 차면 categorize를
 * 한 번 부르고([startCategorizing]), 배정이 오면 폴더를 물질화한 뒤 닫는다([finish]). 누가 무엇을 쓰는지가 계약이다:
 * - 이 서버: [status]·[dispatchedAt]·[attempts]·[finishedAt], 그리고 잡을 닫을 때의 [error].
 * - categorize Lambda: 실패했을 때의 [error] 한 컬럼(photoselect의 `UPDATE (error)` GRANT). 상태는 쓰지 않는다.
 *
 * [DynamicUpdate]인 이유: Lambda가 [error]를 쓰는 동안 이 서버가 다른 컬럼을 갱신할 수 있다. 바뀐 컬럼만 UPDATE해야
 * 이 서버의 오래된 스냅샷(`error = null`)이 Lambda의 실패 기록을 덮지 않는다.
 *
 * 갤러리당 살아 있는 잡이 하나뿐이라는 규칙은 DB의 부분 유니크(`uk_ai_analysis_jobs_active`)가 최종적으로 지킨다.
 */
@Entity
@DynamicUpdate
@Table(name = "ai_analysis_jobs")
class AnalysisJob(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AnalysisStatus = AnalysisStatus.ANALYZING
        protected set

    /** categorize EVENT를 마지막으로 보낸 시각. 타임아웃·재전송의 기준이다. */
    @Column(name = "dispatched_at")
    var dispatchedAt: ZonedDateTime? = null
        protected set

    /** categorize를 몇 번 불렀나. 상한을 넘으면 잡을 닫는다. */
    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0
        protected set

    @Column(name = "finished_at")
    var finishedAt: ZonedDateTime? = null
        protected set

    @Column(name = "error")
    var error: String? = null
        protected set

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AnalysisJob 이다")

    /** 점수가 다 찼다 — categorize를 보내기 직전. 같은 트랜잭션의 `version` 조건이 두 스윕 중 한쪽만 보내게 한다. */
    fun startCategorizing(now: ZonedDateTime) {
        check(status == AnalysisStatus.ANALYZING) { "ANALYZING 잡만 CATEGORIZING 으로 옮길 수 있습니다." }
        status = AnalysisStatus.CATEGORIZING
        redispatchCategorize(now)
    }

    /** categorize가 시간 안에 결과를 남기지 않아 다시 보낸다. 시도 수는 여기서만 오른다. */
    fun redispatchCategorize(now: ZonedDateTime) {
        dispatchedAt = now
        attempts += 1
    }

    /** 호출 자체가 실패했다(권한·스로틀링). 시각을 지워 다음 스윕이 타임아웃을 기다리지 않고 바로 다시 보내게 한다. */
    fun dispatchFailed() {
        dispatchedAt = null
    }

    fun finish(now: ZonedDateTime) {
        status = AnalysisStatus.DONE
        finishedAt = now
    }

    fun fail(error: String, now: ZonedDateTime) {
        status = AnalysisStatus.FAILED
        finishedAt = now
        this.error = error.take(MAX_ERROR_LENGTH)
    }

    companion object {
        /** `error` 컬럼은 text지만 Lambda 쪽과 같은 길이로 자른다. */
        private const val MAX_ERROR_LENGTH = 4000
    }
}
