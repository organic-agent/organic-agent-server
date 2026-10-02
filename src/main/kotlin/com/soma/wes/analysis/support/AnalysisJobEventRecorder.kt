package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisJobEvent
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobEventRepository
import com.soma.wes.analysis.repository.AnalysisJobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 분석 잡의 이력을 남긴다. 로그의 `job.*` 줄을 찍는 자리에서 같이 부른다.
 *
 * 이력은 운영 확인용이라 기록 실패가 파이프라인을 멈추면 안 된다 — 예외를 삼키고 로그만 남긴다. 호출자가 트랜잭션 안이면
 * 그 트랜잭션에 같이 실려, 닫기가 되돌아가면 DONE 이력도 같이 되돌아간다(다음 회차가 다시 닫고 다시 쓴다).
 */
@Component
class AnalysisJobEventRecorder(
    private val eventRepository: AnalysisJobEventRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** @param detail 값이 null 인 항목은 싣지 않는다 */
    fun record(jobId: Long, galleryId: Long, type: AnalysisJobEventType, detail: Map<String, Any?> = emptyMap()) {
        try {
            eventRepository.save(
                AnalysisJobEvent(
                    jobId = jobId,
                    galleryId = galleryId,
                    type = type,
                    detail = detail.filterValues { it != null }.takeIf { it.isNotEmpty() },
                    createdAt = ZonedDateTime.now(clock),
                ),
            )
        } catch (e: RuntimeException) {
            log.error("분석 잡 {} 이력 기록 실패 type={} — 파이프라인은 계속한다", jobId, type, e)
        }
    }

    /**
     * 잡 번호를 모르는 자리(갤러리 단위로 도는 점수 단계)에서 쓴다. 그 갤러리에 진행 중인 잡이 있을 때만 남긴다 —
     * 잡 없이 올라온 사진의 점수 계산은 어느 잡의 이력도 아니다.
     */
    fun recordForActiveJob(galleryId: Long, type: AnalysisJobEventType, detail: Map<String, Any?> = emptyMap()) {
        val job = try {
            analysisJobRepository.findFirstByGalleryIdOrderByIdDesc(galleryId)
        } catch (e: RuntimeException) {
            log.error("갤러리 {} 의 진행 중인 잡 조회 실패 type={} — 이력을 남기지 않는다", galleryId, type, e)
            return
        }
        if (job == null || job.status !in AnalysisStatus.ACTIVE) return
        record(job.requiredId, galleryId, type, detail)
    }
}
