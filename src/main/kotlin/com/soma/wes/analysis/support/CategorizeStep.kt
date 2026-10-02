package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.math.ceil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 3단계: ANALYZING 잡에 categorize를 한 번 보낸다(→ CATEGORIZING). 점수를 내는 것은 [ScoreStep]의 일이라
 * 여기서는 다 찼는지만 본다. 대상 사진이 한 장도 남지 않으면(전부 실패·삭제) 잡을 FAILED로 닫는다.
 * 다 차지 않은 채 진행이 오래 멈추면 기다리기를 끝낸다([watchProgress]) — 점수가 안 나오는 사진 한 장이 잡을 영원히 붙잡지 못한다.
 *
 * 전이는 조건부 UPDATE 한 문장([AnalysisJobRepository.startCategorizing])이라 스윕 둘이 같은 잡을 봐도 1을 받은 쪽만 보낸다.
 * 보내기 자체가 실패하면 전송 시각을 지워 [FolderStep]이 곧바로 다시 보내게 한다.
 */
@Component
class CategorizeStep(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val aiTaskSender: AiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
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
            fail(job, AnalysisFailureCode.NOTHING_TO_ANALYZE, "분석할 사진이 없습니다(실패 ${progress.failed}장)", now)
            return
        }
        if (!progress.isReadyToCategorize) {
            watchProgress(job, progress, now)
            return
        }
        if (analysisJobRepository.startCategorizing(job.requiredId, now) == 0) return

        log.info(
            "event=job.transition job={} gallery={} from=ANALYZING to=CATEGORIZING expected={} embedded={} scored={} failed={}",
            job.requiredId, job.galleryId, progress.expected, progress.embedded, progress.scored, progress.failed,
        )
        eventRecorder.record(
            job.requiredId, job.galleryId, AnalysisJobEventType.CATEGORIZE_SENT,
            mapOf("expected" to progress.expected, "embedded" to progress.embedded, "scored" to progress.scored, "failed" to progress.failed),
        )
        send(job, now)
    }

    /**
     * ANALYZING 의 끝. 진행 값이 바뀌면 시각을 옮기고, 업로드가 잠잠한데 [AnalysisProperties.analyzingStallAfter] 동안 그대로면 멈춘 것이다.
     * - 올라온 사진이 없다(대상 0) → 닫는다. 올라오지 않는 PENDING 이 치워지기를 하루 기다리지 않는다.
     * - 뒤처진 사진이 적다 → 그 사진만 떼어 낸다. 다음 회차에 나머지로 categorize 가 나간다.
     * - 뒤처진 사진이 많다 → 사진이 아니라 실행기의 문제다. 사진은 두고 잡을 닫는다.
     */
    private fun watchProgress(job: AnalysisJob, progress: GalleryAnalysisProgress, now: ZonedDateTime) {
        val progressAt = job.progressAt
        if (progressAt == null || job.progressCount != progress.progressSignature) {
            analysisJobRepository.recordProgress(job.requiredId, progress.progressSignature, now)
            return
        }
        if (progress.livePending > 0) return
        if (progressAt.plus(properties.analyzingStallAfter).isAfter(now)) return

        if (progress.expected == 0L) {
            fail(job, AnalysisFailureCode.NOTHING_TO_ANALYZE, "올라온 사진이 없습니다(대기 ${progress.pending}장)", now)
            return
        }
        val detachable = maxOf(MIN_DETACHABLE_PHOTOS, ceil(progress.expected * properties.stallDetachRatio).toLong())
        if (progress.unscored > detachable) {
            log.warn(
                "event=job.stalled job={} gallery={} stalledPhotos={} expected={} action=fail",
                job.requiredId, job.galleryId, progress.unscored, progress.expected,
            )
            fail(job, AnalysisFailureCode.SCORE_STAGE_DOWN, "임베딩·점수가 진행되지 않습니다(${progress.unscored}/${progress.expected}장)", now)
            return
        }

        val stalledPhotoIds = photoPipelineRepository.markAnalysisStalled(job.galleryId, now)
        log.warn(
            "event=job.stalled job={} gallery={} stalledPhotos={} expected={} action=detach photoIds={}",
            job.requiredId, job.galleryId, stalledPhotoIds.size, progress.expected,
            stalledPhotoIds.take(MAX_LOGGED_PHOTO_IDS).joinToString(","),
        )
        eventRecorder.record(
            job.requiredId, job.galleryId, AnalysisJobEventType.PHOTOS_DETACHED,
            mapOf("stalledPhotos" to stalledPhotoIds.size, "expected" to progress.expected, "photoIds" to stalledPhotoIds.take(MAX_LOGGED_PHOTO_IDS)),
        )
    }

    private fun fail(job: AnalysisJob, code: AnalysisFailureCode, error: String, now: ZonedDateTime) {
        if (analysisJobRepository.fail(job.requiredId, AnalysisJob.trimError(error), code, now) == 0) return
        log.warn(
            "event=job.transition job={} gallery={} from=ANALYZING to=FAILED errorCode={} trigger={} retry={} error=\"{}\"",
            job.requiredId, job.galleryId, code, job.trigger, job.retryCount, error,
        )
        eventRecorder.record(
            job.requiredId, job.galleryId, AnalysisJobEventType.FAILED,
            mapOf("from" to AnalysisStatus.ANALYZING.name, "errorCode" to code.name, "error" to error),
        )
    }

    private fun send(job: AnalysisJob, now: ZonedDateTime) {
        try {
            aiTaskSender.send(AiTaskDto.Categorize(galleryId = job.galleryId, jobId = job.requiredId, conceptCount = job.conceptCount))
        } catch (e: AnalysisException) {
            log.warn("categorize 전송 실패 — 곧바로 다시 보낸다: job={} code={}", job.requiredId, e.errorCode.code)
            eventRecorder.record(job.requiredId, job.galleryId, AnalysisJobEventType.CATEGORIZE_SEND_FAILED, mapOf("code" to e.errorCode.code))
            analysisJobRepository.clearDispatchedAt(job.requiredId, now)
        }
    }

    companion object {
        /** 대상이 적은 갤러리에서도 이만큼은 떼어 낼 수 있다 — 20장 갤러리의 한 장이 잡을 닫지 않게 한다. */
        private const val MIN_DETACHABLE_PHOTOS = 1L

        /** 로그 한 줄에 싣는 사진 id 상한. 전체 수는 `stalledPhotos=`로 따로 찍는다. */
        private const val MAX_LOGGED_PHOTO_IDS = 20
    }
}
