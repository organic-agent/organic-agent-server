package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiFailureDto
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.support.AnalysisJobEventRecorder
import com.soma.wes.analysis.support.ScoreStep
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Lambda 가 끝내 실패한 호출 하나(실패 큐, #286 (c) ②)에 반응한다 — **직접 다시 보내지 않고, 그 작업의 재전송 대기를 앞당기기만 한다.**
 * 다시 보내는 것은 지금처럼 스윕의 단계들이다(임베더 [com.soma.wes.analysis.support.EmbedStep] · categorize [com.soma.wes.analysis.support.FolderStep] ·
 * rank [com.soma.wes.analysis.support.RankStep] · score 폴백 [ScoreStep]). 보내는 길이 하나로 남아 시도 상한·선점·중복 방지를 그 단계들이 그대로 맡는다.
 *
 * 앞당기는 정도는 몇 번째 실패인지로 정한다([AnalysisProperties.FailureQueue.retryDelayAfter] — 처음은 곧바로, 다음은 점점 늦게). 상한에 닿은 실패는
 * 기다리지 않아 곧바로 실패로 닫힌다. 시간 기반 재전송은 그대로 남아, 실패 큐로 오지 않는 경우(서버가 꺼져 있던 동안 등)의 안전망이 된다.
 * 실패한 뒤 이미 다시 보낸 작업·끝난 작업은 건드리지 않으므로, 같은 메시지를 두 번 받아도 결과가 같다. 실패마다 잡 이력(`*_FAILED`)을 남긴다.
 */
@Service
class AiFailureService(
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val analysisJobRepository: AnalysisJobRepository,
    private val scoreStep: ScoreStep,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(failure: AiFailureDto) {
        val now = ZonedDateTime.now(clock)
        when (val task = failure.task) {
            is AiTaskDto.Embed -> LogContext.gallery(task.galleryId) { embed(task, failure, now) }
            is AiTaskDto.Score -> LogContext.gallery(task.galleryId) { score(task, failure, now) }
            is AiTaskDto.Categorize -> LogContext.gallery(task.galleryId, task.jobId) { categorize(task, failure, now) }
            is AiTaskDto.Rank -> LogContext.gallery(task.galleryId) { rank(task, failure, now) }
            is AiTaskDto.ExactPhoto -> logFailure(failure, task.galleryId, action = "none")
            null -> logFailure(failure, galleryId = null, action = "unknown")
        }
    }

    /** 배치의 배정을 앞당겨 푼다 — 다음 스윕이 다시 집는다. 사진마다 시도 수가 다를 수 있어 사진 단위로 기다릴 시간을 정한다. */
    private fun embed(task: AiTaskDto.Embed, failure: AiFailureDto, now: ZonedDateTime) {
        val expedited = photoPipelineRepository.expediteEmbedRedispatch(task.photoIds, failure.failedAt) { attempts ->
            now.minus(properties.embedRedispatchAfter).plus(retryDelay(attempts, properties.embedMaxAttempts))
        }
        logFailure(failure, task.galleryId, action = "expedited", "photos" to task.photoIds.size, "expedited" to expedited)
        eventRecorder.recordForActiveJob(
            task.galleryId, AnalysisJobEventType.EMBED_FAILED,
            detailOf(failure) + mapOf("photos" to task.photoIds.size, "expedited" to expedited),
        )
    }

    private fun score(task: AiTaskDto.Score, failure: AiFailureDto, now: ZonedDateTime) {
        val attempt = scoreStep.expediteFallback(task.galleryId) { attempt -> now.plus(retryDelay(attempt, properties.scoreFallbackMax)) }
        logFailure(failure, task.galleryId, action = "expedited", "photos" to task.photoIds.size, "attempt" to attempt)
        eventRecorder.recordForActiveJob(
            task.galleryId, AnalysisJobEventType.SCORE_FAILED,
            detailOf(failure) + mapOf("photos" to task.photoIds.size, "attempt" to attempt),
        )
    }

    private fun categorize(task: AiTaskDto.Categorize, failure: AiFailureDto, now: ZonedDateTime) {
        val job = analysisJobRepository.findByIdOrNull(task.jobId)
        if (job == null || job.status != AnalysisStatus.CATEGORIZING) {
            logFailure(failure, task.galleryId, action = "ignored", "status" to job?.status)
            return
        }
        val delay = retryDelay(job.attempts, properties.categorizeMaxAttempts)
        val expedited = analysisJobRepository.expediteCategorize(
            id = job.requiredId,
            attempts = job.attempts,
            failedAt = failure.failedAt,
            dispatchedAt = now.minus(properties.categorizeTimeout).plus(delay),
            now = now,
        )
        recordJobFailure(job, AnalysisJobEventType.CATEGORIZE_FAILED, failure, job.attempts, properties.categorizeMaxAttempts, delay, expedited)
    }

    private fun rank(task: AiTaskDto.Rank, failure: AiFailureDto, now: ZonedDateTime) {
        val job = analysisJobRepository.findFirstByGalleryIdAndStatusOrderByIdDesc(task.galleryId, AnalysisStatus.DONE)
        if (job == null) {
            logFailure(failure, task.galleryId, action = "ignored", "status" to null)
            return
        }
        val rank = properties.qualityStage
        val delay = retryDelay(job.rankAttempts, rank.rankMaxAttempts)
        val expedited = analysisJobRepository.expediteRank(
            id = job.requiredId,
            attempts = job.rankAttempts,
            failedAt = failure.failedAt,
            dispatchedAt = now.minus(rank.rankTimeout).plus(delay),
            now = now,
        )
        recordJobFailure(job, AnalysisJobEventType.RANK_FAILED, failure, job.rankAttempts, rank.rankMaxAttempts, delay, expedited)
    }

    private fun recordJobFailure(
        job: AnalysisJob,
        type: AnalysisJobEventType,
        failure: AiFailureDto,
        attempt: Int,
        maxAttempts: Int,
        delay: Duration,
        expedited: Int,
    ) {
        val action = if (expedited > 0) "expedited" else "ignored"
        val retryIn = delay.seconds.takeIf { expedited > 0 }
        logFailure(failure, job.galleryId, action, "job" to job.requiredId, "attempt" to attempt, "max" to maxAttempts, "retryInSeconds" to retryIn)
        eventRecorder.record(
            job.requiredId, job.galleryId, type,
            detailOf(failure) + mapOf("attempt" to attempt, "max" to maxAttempts, "action" to action, "retryInSeconds" to retryIn),
        )
    }

    private fun retryDelay(failedAttempt: Int, maxAttempts: Int): Duration =
        properties.failureQueue.retryDelayAfter(failedAttempt, maxAttempts)

    private fun detailOf(failure: AiFailureDto): Map<String, Any?> = mapOf(
        "function" to failure.functionName,
        "condition" to failure.condition,
        "errorType" to failure.errorType,
        "errorMessage" to failure.errorMessage,
    )

    private fun logFailure(failure: AiFailureDto, galleryId: Long?, action: String, vararg fields: Pair<String, Any?>) {
        val extra = fields.filter { (_, value) -> value != null }.joinToString("") { (key, value) -> " $key=$value" }
        log.warn(
            "event=ai.failed function={} gallery={} condition={} errorType={} action={}{} error=\"{}\"",
            failure.functionName, galleryId, failure.condition, failure.errorType, action, extra, failure.errorMessage?.take(MAX_LOGGED_ERROR),
        )
    }

    companion object {
        /** 로그 한 줄에 싣는 오류 문구 길이. 전체(500자)는 잡 이력에 남는다. */
        private const val MAX_LOGGED_ERROR = 200
    }
}
