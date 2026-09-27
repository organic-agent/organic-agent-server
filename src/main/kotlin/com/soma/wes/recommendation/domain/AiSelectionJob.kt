package com.soma.wes.recommendation.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Convert
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
 * 폴더별 AI 추천 잡. 요청이 [AiJobStatus.PENDING] 행을 만들고, 이 서버의 실행기
 * ([com.soma.wes.recommendation.service.AiSelectionJobRunner])가 집어([claim]) 계산한 뒤 닫는다([finish]·[fail]).
 * 셀렉당 살아 있는 잡 하나는 DB의 부분 유니크(`uk_ai_selection_jobs_active`)가 최종적으로 지킨다.
 *
 * 잡 행은 큐이자 프론트 폴링용 상태다 — 추천 표시(1단계)는 DONE 전에 먼저 생기고 이유 문장(2단계)이 뒤따른다.
 */
@Entity
@Table(name = "ai_selection_jobs")
class AiSelectionJob(

    @Column(name = "selection_id", nullable = false, updatable = false)
    val selectionId: Long,

    @Convert(converter = AiSelectionMode.DbConverter::class)
    @Column(name = "mode", nullable = false, updatable = false, length = 10)
    val mode: AiSelectionMode,

    /**
     * 요청 시점에 기준으로 삼은 AI 폴더 세트의 키([com.soma.wes.analysis.domain.AnalysisJob]의 id = `concept_folders.analysis_job_id`).
     * 프론트가 현재 보는 세트와 다르면 "추천을 다시 받으세요"를 띄우는 재현용 값이다.
     */
    // [GLOSSARY-1 2026-09-27] folderSetJobId → analysisJobId (용어집: 폴더 세트의 키는 analysis_job_id). 응답 필드는 4단계에서 바꾼다.
    // [GLOSSARY-2 2026-09-27] 컬럼 folder_set_job_id → analysis_job_id (V23)
    @Column(name = "analysis_job_id", updatable = false)
    val analysisJobId: Long? = null,

    /**
     * 이 잡의 범위. null이면 갤러리 전체(모든 세부폴더 + 미분류)를 한 라운드로 계산한다. 값이 있으면 그
     * 세부폴더에 요청 시점에 든 사진만 대상으로 하고, 그 사진들의 기존 추천만 지우고 다시 쓴다.
     */
    @Column(name = "detail_folder_id", updatable = false)
    val detailFolderId: Long? = null,

    @Column(name = "request_prompt", updatable = false, length = 1000)
    val prompt: String? = null,

    @Column(name = "requested_target_count", updatable = false)
    val targetCount: Int? = null,

) : BaseEntity() {

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_query", columnDefinition = "jsonb")
    var resolvedQuery: ResolvedRecommendationQuery? = null
        protected set

    fun resolveQuery(query: ResolvedRecommendationQuery) {
        check(resolvedQuery == null) { "이미 해석한 추천 조건은 변경할 수 없습니다." }
        resolvedQuery = query
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 이 잡이 만든 추천 라운드 번호. 1단계(추천 INSERT)에서 정해진다 — 그 전에는 null이다. */
    @Column(name = "round")
    var round: Int? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AiJobStatus = AiJobStatus.PENDING
        protected set

    @Column(name = "started_at")
    var startedAt: ZonedDateTime? = null
        protected set

    @Column(name = "finished_at")
    var finishedAt: ZonedDateTime? = null
        protected set

    @Column(name = "error")
    var error: String? = null
        protected set

    /** 끝난 잡의 요약(라운드·장수·폴더별 추천 수·이유 분포·소요). 운영 확인용이고 화면은 읽지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    var result: Map<String, Any?>? = null
        protected set

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AiSelectionJob 이다")

    /** 1단계에서 라운드가 정해진 잡. 실행 중 죽었다가 다시 집으면 추천은 두지 않고 이유만 다시 채운다. */
    fun assignRound(round: Int) {
        this.round = round
    }

    fun finish(result: Map<String, Any?>, at: ZonedDateTime) {
        status = AiJobStatus.DONE
        finishedAt = at
        this.result = result
    }

    fun fail(error: String, at: ZonedDateTime) {
        status = AiJobStatus.FAILED
        finishedAt = at
        this.error = error.take(MAX_ERROR_LENGTH)
    }

    /** 실행 중 프로세스가 죽어 RUNNING으로 남은 잡을 다시 줄에 세운다. 기동 복구가 부른다. */
    fun requeue() {
        status = AiJobStatus.PENDING
        startedAt = null
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 4000
    }
}
