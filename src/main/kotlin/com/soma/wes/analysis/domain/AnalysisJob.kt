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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 갤러리 전수 분석 잡 — Lambda 셋(embedder → score → categorize)을 한 줄로 묶는 상태 기계. DB 행이 곧 큐 항목이다.
 *
 * 진실은 [stage]·[stageStatus](지금 어느 Lambda 차례이며 그 단계가 어디까지 왔는지)다. 바깥 [status]는 그 **투영**이다 —
 * 첫 단계를 보내면 RUNNING, 마지막 단계가 끝나면 DONE, 단계 실패·시도 소진이면 FAILED. 컬럼으로 두는 이유는 갤러리당 활성 잡
 * 하나를 지키는 부분 유니크 인덱스와 프론트 계약이다. 누가 무엇을 쓰는지가 계약이다:
 * - 이 서버: [status]·[stage]·[stageAttempts]·[dispatchedAt]·[observedProgress]·[startedAt]·[finishedAt].
 * - Lambda: [stageStatus]의 시작(claim)·끝, [heartbeatAt], [result]의 단계별 키, [error]. 아직 단계 상태를 쓰지 않는
 *   Lambda(AI repo Phase 0 이전)는 [status]를 직접 DONE·FAILED로 닫는다 — 종료 상태는 그대로 받아들이고 완료 알림만 별도로 스윕한다.
 * - [AnalysisStage.EMBED]는 예외로 이 서버가 `photo_analysis`를 관측해 단계를 열고 닫는다. 임베더는 잡을 모른다.
 *
 * 갤러리당 살아 있는 잡이 하나뿐이라는 규칙은 DB의 부분 유니크(`uk_ai_analysis_jobs_active`)가 최종적으로 지킨다.
 */
@Entity
@Table(name = "ai_analysis_jobs")
class AnalysisJob(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 10)
    val mode: AnalysisMode,

    /** 이미 벡터가 있는 사진도 다시 계산한다. 임베더 `--force`와 같고, 관측 완료 판정의 기준(재적재 시각)도 바꾼다. */
    @Column(name = "force", nullable = false, updatable = false)
    val force: Boolean = false,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AnalysisStatus = AnalysisStatus.PENDING
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 20)
    var stage: AnalysisStage? = mode.firstStage
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_status", length = 20)
    var stageStatus: AnalysisStatus? = AnalysisStatus.PENDING
        protected set

    /** 이 단계를 몇 번 불렀나. 정체·실패로 다시 부를 때마다 오르고, 상한을 넘으면 잡을 닫는다. 15분 중단 재개는 세지 않는다. */
    @Column(name = "stage_attempts", nullable = false)
    var stageAttempts: Int = 0
        protected set

    /** 마지막으로 EVENT를 보낸 시각. null이면 아직 안 보냈거나 호출 자체가 실패해 다시 보내야 한다. */
    @Column(name = "dispatched_at")
    var dispatchedAt: ZonedDateTime? = null
        protected set

    /** 단계가 살아 있다는 마지막 신호. Lambda가 배치마다, EMBED는 이 서버가 진행을 관측할 때 갱신한다. */
    @Column(name = "heartbeat_at")
    var heartbeatAt: ZonedDateTime? = null
        protected set

    /** EMBED 관측용 — 마지막 스윕에서 센 완료 사진 수. 이 값이 늘면 [heartbeatAt]이 갱신된다. */
    @Column(name = "observed_progress", nullable = false)
    var observedProgress: Int = 0
        protected set

    @Column(name = "started_at")
    var startedAt: ZonedDateTime? = null
        protected set

    @Column(name = "finished_at")
    var finishedAt: ZonedDateTime? = null
        protected set

    /** 완료 알림과 같은 트랜잭션에서만 기록한다. 옛 Lambda의 status 직접 갱신도 스윕이 이 마커로 찾는다. */
    @Column(name = "completion_notified_at")
    var completionNotifiedAt: ZonedDateTime? = null
        protected set

    @Column(name = "error")
    var error: String? = null
        protected set

    /**
     * Lambda가 남기는 결과 요약(`{progress:{processed,total}, embed:{…}, score:{…}, categorize:{…}}`). 단계마다 `||`로
     * 병합되므로 이 서버는 진행률(`progress`)만 꺼내 응답에 노출한다 — 나머지 키는 배치의 것이라 스키마를 강제하지 않는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    val result: Map<String, Any?>? = null

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AnalysisJob 이다")

    /** 현재 단계가 이미 누군가(Lambda 또는 이 서버의 EMBED 관측)에게 잡혀 도는 중인가. 단계 상태를 쓰는 Lambda 기준. */
    val isStageClaimed: Boolean
        get() = stageStatus != AnalysisStatus.PENDING

    /**
     * EVENT를 보내기 직전. 호출이 실패하면 [dispatchFailed]로 되돌린다.
     * 첫 단계를 보내는 순간 잡은 "진행 중"이다 — [status]는 단계 필드의 투영이라 여기서 RUNNING으로 올린다.
     */
    fun dispatch(now: ZonedDateTime) {
        dispatchedAt = now
        stageAttempts += 1
        if (status == AnalysisStatus.PENDING) {
            status = AnalysisStatus.RUNNING
            startedAt = now
        }
    }

    /**
     * 호출 자체가 실패했다(권한·스로틀링). 시도 수는 남기고 단계를 다시 줄에 세워 다음 스윕이 기다리지 않고 보내게 한다.
     * EMBED는 관측자가 이미 열어 둔 상태라 되돌리지 않으면 정체 판정(20분)까지 기다리게 된다.
     */
    fun dispatchFailed() {
        requeueStage()
    }

    /** EMBED 단계를 이 서버가 대신 연다 — 임베더는 잡 행을 쓰지 않는다. */
    fun openStageByObserver(progress: Int, now: ZonedDateTime) {
        stageStatus = AnalysisStatus.RUNNING
        observedProgress = progress
        heartbeatAt = now
    }

    /** 관측한 진행이 늘었으면 살아 있다는 신호로 친다. */
    fun observeProgress(progress: Int, now: ZonedDateTime) {
        if (progress <= observedProgress) return
        observedProgress = progress
        heartbeatAt = now
    }

    fun completeStage(now: ZonedDateTime) {
        stageStatus = AnalysisStatus.DONE
        heartbeatAt = now
    }

    /** 다음 단계로. 시도·시각·관측은 단계의 것이라 전부 초기화한다. */
    fun advance(next: AnalysisStage) {
        stage = next
        stageStatus = AnalysisStatus.PENDING
        stageAttempts = 0
        dispatchedAt = null
        heartbeatAt = null
        observedProgress = 0
    }

    /** 정체(하트비트 끊김)로 판정된 단계를 다시 줄에 세운다. 시도 수는 [dispatch]가 올린다. */
    fun requeueStage() {
        stageStatus = AnalysisStatus.PENDING
        dispatchedAt = null
        heartbeatAt = null
    }

    fun finish(now: ZonedDateTime) {
        status = AnalysisStatus.DONE
        finishedAt = now
    }

    fun markCompletionNotified(now: ZonedDateTime) {
        check(status == AnalysisStatus.DONE) { "완료된 분석 잡만 알림을 기록할 수 있습니다." }
        if (completionNotifiedAt == null) completionNotifiedAt = now
    }

    fun fail(error: String, now: ZonedDateTime) {
        status = AnalysisStatus.FAILED
        finishedAt = now
        this.error = error.take(MAX_ERROR_LENGTH)
    }

    companion object {
        /** `error` 컬럼은 text지만 Lambda 쪽(`jobs.fail`)과 같은 길이로 자른다. */
        private const val MAX_ERROR_LENGTH = 4000
    }
}
