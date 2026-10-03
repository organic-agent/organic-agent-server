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
 * 한 번 부르고, 배정이 오면 폴더를 물질화한 뒤 닫는다. 전이는 엔티티 메서드가 아니라 [com.soma.wes.analysis.repository.AnalysisJobRepository]의
 * 조건부 UPDATE다 — 스윕 둘 중 한쪽만 옮기게 하는 것이 그 문장의 영향 행 수다. 누가 무엇을 쓰는지가 계약이다:
 * - 이 서버: [status]·[dispatchedAt]·[attempts]·[finishedAt]·진행 감시([progressCount]·[progressAt])·[categorizingAt]·
 *   [materializeAttempts], 그리고 잡을 닫을 때의 [error]·[errorCode].
 * - categorize Lambda: 실패했을 때의 [error] 한 컬럼(photoselect의 `UPDATE (error)` GRANT). 상태는 쓰지 않는다.
 *
 * [DynamicUpdate]인 이유: Lambda가 [error]를 쓰는 동안 이 서버가 다른 컬럼을 갱신할 수 있다. 바뀐 컬럼만 UPDATE해야
 * 이 서버의 오래된 스냅샷(`error = null`)이 Lambda의 실패 기록을 덮지 않는다.
 *
 * 갤러리당 살아 있는 잡이 하나뿐이라는 규칙은 DB의 부분 유니크(`uk_analysis_jobs_active`)가 최종적으로 지킨다.
 */
@Entity
@DynamicUpdate
// [GLOSSARY-2 2026-09-27] 테이블 ai_analysis_jobs → analysis_jobs (V23, 용어집 D4)
@Table(name = "analysis_jobs")
class AnalysisJob(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    /** 사용자가 기억하는 컨셉 수(선택). categorize 가 촬영 시각 구간을 이 수의 1층으로 묶는다. null 이면 AI 가 정한다. */
    @Column(name = "concept_count", updatable = false)
    val conceptCount: Int? = null,

    /** 누가 만들었나. 운영 로그와 알림이 "사용자가 누른 잡인가 서버가 만든 잡인가"를 가르는 값이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, updatable = false, length = 10)
    val trigger: AnalysisTrigger = AnalysisTrigger.USER,

    /**
     * 몇 번째 자동 재시도인가. [AnalysisTrigger.RETRY]가 아니면 0이고, 재시도 잡은 앞 잡의 값에 1을 더한다.
     * 일시적 실패가 이어질 때 정해진 횟수에서 멈추게 하는 값이다 — 사용자가 다시 요청하면 0부터 다시 센다.
     */
    @Column(name = "retry_count", nullable = false, updatable = false)
    val retryCount: Int = 0,
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

    /** FAILED 로 닫힌 이유. 이 컬럼이 생기기 전에 닫힌 잡과 살아 있는 잡은 null 이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 40)
    var errorCode: AnalysisFailureCode? = null
        protected set

    /**
     * ANALYZING 의 진행 감시 값 — 스윕이 본 갤러리의 진행([com.soma.wes.photo.repository.projection.GalleryAnalysisProgress.progressSignature])과
     * 그 값이 마지막으로 바뀐 시각. 시각이 오래 멈춰 있으면 임베딩·점수가 멈춘 것이다.
     */
    @Column(name = "progress_count")
    var progressCount: Int? = null
        protected set

    @Column(name = "progress_at")
    var progressAt: ZonedDateTime? = null
        protected set

    /** CATEGORIZING 에 들어간 시각. [dispatchedAt]은 다시 보낼 때마다 옮겨지므로 잡 전체 기한은 이 값으로 잰다. */
    @Column(name = "categorizing_at")
    var categorizingAt: ZonedDateTime? = null
        protected set

    /** 폴더 만들기가 예상 밖 예외로 실패한 횟수. 상한에서 잡을 닫는다 — 없으면 5초마다 끝없이 다시 시도한다. */
    @Column(name = "materialize_attempts", nullable = false)
    var materializeAttempts: Int = 0
        protected set

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AnalysisJob 이다")

    companion object {
        /** `error` 컬럼은 text지만 Lambda 쪽과 같은 길이로 자른다. */
        private const val MAX_ERROR_LENGTH = 4000

        /** 잡을 FAILED로 닫을 때 남길 오류 — 길이를 Lambda 쪽과 맞춘다. */
        fun trimError(error: String): String = error.take(MAX_ERROR_LENGTH)
    }
}
