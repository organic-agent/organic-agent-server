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

/**
 * 폴더별 AI 추천 잡. [AiAnalysisJob]과 같은 규약이다 — 이 서버는 [AiJobStatus.PENDING] 행을
 * 만드는 것까지만 하고, AI 워커가 집어가 상태·시각·[round]·오류를 직접 UPDATE 하므로 그 컬럼들은
 * 읽기 전용 `val`이다. 셀렉당 살아 있는 잡 하나는 DB의 부분 유니크(`uk_ai_selection_jobs_active`)가
 * 최종적으로 지킨다. `result`(jsonb)는 이 서버가 아직 읽지 않아 매핑하지 않았다.
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
     * 요청 시점에 기준으로 삼은 AI 폴더 세트([AiAnalysisJob]의 id = `photo_folder_groups.analysis_job_id`).
     * 프론트가 현재 보는 세트와 다르면 "추천을 다시 받으세요"를 띄우는 재현용 값이다.
     */
    @Column(name = "folder_set_job_id", updatable = false)
    val folderSetJobId: Long? = null,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** 워커가 이 잡으로 만든 추천 라운드 번호. 끝나기 전에는 null이다. */
    @Column(name = "round")
    val round: Int? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: AiJobStatus = AiJobStatus.PENDING

    @Column(name = "started_at")
    val startedAt: ZonedDateTime? = null

    @Column(name = "finished_at")
    val finishedAt: ZonedDateTime? = null

    @Column(name = "error")
    val error: String? = null

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AiSelectionJob 이다")
}
