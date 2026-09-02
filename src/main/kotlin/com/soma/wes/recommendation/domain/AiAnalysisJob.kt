package com.soma.wes.recommendation.domain

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
 * 갤러리 전수 분석 잡. DB 행이 곧 큐 항목이다 — 별도 큐 서비스가 없다.
 *
 * 이 서버는 [AiJobStatus.PENDING] 행을 만드는 것까지만 한다. 분석 배치가 PENDING을 집어가
 * `photo_analysis`를 채우고 상태·시각·오류를 직접 UPDATE 하므로, 그 컬럼들은 읽기 전용 `val`이다.
 * 갤러리당 살아 있는 잡이 하나뿐이라는 규칙은 DB의 부분 유니크(`uk_ai_analysis_jobs_active`)가
 * 최종적으로 지킨다. `result`(jsonb)는 이 서버가 아직 읽지 않아 매핑하지 않았다.
 */
@Entity
@Table(name = "ai_analysis_jobs")
class AiAnalysisJob(

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 10)
    val mode: AiAnalysisMode = AiAnalysisMode.FULL,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: AiJobStatus = AiJobStatus.PENDING

    @Column(name = "started_at")
    val startedAt: ZonedDateTime? = null

    @Column(name = "finished_at")
    val finishedAt: ZonedDateTime? = null

    @Column(name = "error")
    val error: String? = null

    /**
     * 배치가 남기는 결과 요약. 이 서버는 진행률(`progress: {processed, total}`)만 꺼내 응답에
     * 노출한다 — 나머지 키는 배치의 것이라 스키마를 강제하지 않는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    val result: Map<String, Any?>? = null

    val requiredId: Long
        get() = id ?: kotlin.error("아직 저장되지 않은 AiAnalysisJob 이다")
}
