package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 5단계(#274 2물결, ADR 0002 B): 폴더가 만들어진 뒤 추천 재료(백분위·연사 대표 순위)를 채운다. 폴더는 화질 점수(score 2단계)를
 * 기다리지 않고 만들어지므로 그 갤러리의 백분위는 비어 있다. 화질 점수가 갤러리 전부에 차면 categorize rank 모드를 한 번 보낸다.
 *
 * 보내는 조건: [AnalysisProperties.QualityStage.enabled] ∧ 그룹은 있는데 백분위가 빈 사진이 있고 화질 점수 대기는 없는 갤러리
 * ([PhotoPipelineRepository.findGalleryIdsReadyToRank]) ∧ 살아 있는 잡이 없음 ∧ 마지막 DONE 잡이 처음이거나 보낸 지
 * [AnalysisProperties.QualityStage.rankTimeout]이 지났고 [AnalysisProperties.QualityStage.rankMaxAttempts] 전.
 * 살아 있는 잡이 있으면 그 잡의 categorize 가 점수가 다 찼을 때 순위까지 한 번에 쓰므로 기다린다.
 *
 * 선점은 조건부 UPDATE([AnalysisJobRepository.startRanking])라 스윕 둘이 같은 갤러리를 봐도 1을 받은 쪽만 보낸다. 상한에 닿으면
 * 다시 보내지 않는다 — 폴더는 이미 있고 추천만 "준비 중"으로 남는다. 상한 도달은 잡 하나에 한 번만 오류로 찍는다(인메모리).
 */
@Component
class RankStep(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val aiTaskSender: AiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 상한에 닿았다고 이미 알린 잡. 5초마다 같은 오류를 찍지 않게 한다. */
    private val gaveUpJobIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun advance() {
        if (!properties.qualityStage.enabled) return
        for (galleryId in photoPipelineRepository.findGalleryIdsReadyToRank()) {
            try {
                advance(galleryId)
            } catch (e: RuntimeException) {
                log.error("갤러리 {} rank 단계 실패 — 다음 회차에 다시 본다", galleryId, e)
            }
        }
    }

    private fun advance(galleryId: Long) {
        if (analysisJobRepository.existsByGalleryIdAndStatusIn(galleryId, AnalysisStatus.ACTIVE)) return
        val job = analysisJobRepository.findFirstByGalleryIdAndStatusOrderByIdDesc(galleryId, AnalysisStatus.DONE) ?: return
        LogContext.gallery(galleryId, job.requiredId) { send(job) }
    }

    private fun send(job: AnalysisJob) {
        val now = ZonedDateTime.now(clock)
        val rank = properties.qualityStage
        if (analysisJobRepository.startRanking(job.requiredId, now, now.minus(rank.rankTimeout), rank.rankMaxAttempts) == 0) {
            warnGaveUp(job, rank.rankMaxAttempts)
            return
        }

        val attempt = job.rankAttempts + 1
        log.info("event=rank.sent job={} gallery={} attempt={}", job.requiredId, job.galleryId, attempt)
        eventRecorder.record(job.requiredId, job.galleryId, AnalysisJobEventType.RANK_SENT, mapOf("attempt" to attempt))
        try {
            aiTaskSender.send(AiTaskDto.Rank(galleryId = job.galleryId))
        } catch (e: AnalysisException) {
            log.warn("rank 전송 실패 — 곧바로 다시 보낸다: job={} code={}", job.requiredId, e.errorCode.code)
            eventRecorder.record(job.requiredId, job.galleryId, AnalysisJobEventType.RANK_SEND_FAILED, mapOf("code" to e.errorCode.code))
            analysisJobRepository.clearRankDispatchedAt(job.requiredId, now)
        }
    }

    private fun warnGaveUp(job: AnalysisJob, maxAttempts: Int) {
        if (job.rankAttempts < maxAttempts || !gaveUpJobIds.add(job.requiredId)) return
        log.error(
            "event=rank.gave_up job={} gallery={} attempts={} — 추천이 준비 중으로 남는다",
            job.requiredId, job.galleryId, job.rankAttempts,
        )
    }
}
