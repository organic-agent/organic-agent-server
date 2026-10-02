package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 3단계: ANALYZING 잡에 categorize를 한 번 보낸다(→ CATEGORIZING). 점수를 내는 것은 [ScoreStep]의 일이라
 * 여기서는 다 찼는지만 본다. 대상 사진이 한 장도 남지 않으면(전부 실패·삭제) 잡을 FAILED로 닫는다.
 *
 * 전이는 조건부 UPDATE 한 문장([AnalysisJobRepository.startCategorizing])이라 스윕 둘이 같은 잡을 봐도 1을 받은 쪽만 보낸다.
 * 보내기 자체가 실패하면 전송 시각을 지워 [FolderStep]이 곧바로 다시 보내게 한다.
 */
@Component
class CategorizeStep(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val aiTaskSender: AiTaskSender,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun advance() {
        for (job in analysisJobRepository.findAllByStatusOrderByIdAsc(AnalysisStatus.ANALYZING)) {
            try {
                LogContext.gallery(job.galleryId, job.requiredId) { advance(job) }
            } catch (e: RuntimeException) {
                log.error("분석 잡 {} categorize 단계 실패 — 다음 회차에 다시 본다", job.requiredId, e)
            }
        }
    }

    private fun advance(job: AnalysisJob) {
        val now = ZonedDateTime.now(clock)
        val progress = photoPipelineRepository.progressOf(job.galleryId, liveSince = now.minus(properties.uploadQuietAfter))

        if (progress.hasNothingToAnalyze) {
            analysisJobRepository.fail(job.requiredId, AnalysisJob.trimError("분석할 사진이 없습니다(실패 ${progress.failed}장)"), now)
            log.info(
                "event=job.transition job={} gallery={} from=ANALYZING to=FAILED failed={}",
                job.requiredId, job.galleryId, progress.failed,
            )
            return
        }
        if (!progress.isReadyToCategorize) return
        if (analysisJobRepository.startCategorizing(job.requiredId, now) == 0) return

        log.info(
            "event=job.transition job={} gallery={} from=ANALYZING to=CATEGORIZING expected={} embedded={} scored={} failed={}",
            job.requiredId, job.galleryId, progress.expected, progress.embedded, progress.scored, progress.failed,
        )
        send(job, now)
    }

    private fun send(job: AnalysisJob, now: ZonedDateTime) {
        try {
            aiTaskSender.send(AiTaskDto.Categorize(galleryId = job.galleryId, jobId = job.requiredId, conceptCount = job.conceptCount))
        } catch (e: AnalysisException) {
            log.warn("categorize 전송 실패 — 곧바로 다시 보낸다: job={} code={}", job.requiredId, e.errorCode.code)
            analysisJobRepository.clearDispatchedAt(job.requiredId, now)
        }
    }
}
