package com.soma.wes.analysis.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.ZonedDateTime

/**
 * 분석 잡의 이력 한 줄 — 잡이 언제 어떤 단계를 넘었는지. 한 번 쓰고 고치지 않는다(수정 시각·버전이 없다).
 * [AnalysisJob] 행은 마지막 상태만 말하므로, 재전송·폴백·실패 이유처럼 지나간 일은 여기에만 남는다.
 */
@Entity
@Table(name = "analysis_job_events")
class AnalysisJobEvent(
    @Column(name = "job_id", nullable = false, updatable = false)
    val jobId: Long,

    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    val type: AnalysisJobEventType,

    /** 종류마다 다른 부가 값(장수·시도 횟수·오류 코드 등). 운영 확인용이고 판단에 쓰지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    val detail: Map<String, Any?>? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: ZonedDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
