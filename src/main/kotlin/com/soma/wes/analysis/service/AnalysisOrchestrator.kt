package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStage
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.StageDispatchDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 분석 잡의 상태 기계. 요청 직후([dispatch])와 주기 스윕([sweep])이 같은 [step]을 지난다 — 요청 경로가 유실돼도
 * 스윕이 같은 판단을 다시 내리므로 별도의 복구 로직이 없다. 상태가 전부 DB에 있어 스윕 한 번이 곧 기동 복구다.
 *
 * 한 잡의 한 걸음은 짧은 트랜잭션 하나다(전이 결정 + 컬럼 갱신). Lambda 호출은 트랜잭션 밖이다 — 커넥션을 문 채
 * 네트워크를 기다리지 않고, 호출이 실패하면 [AnalysisJob.dispatchFailed]로 되돌려 다음 스윕이 다시 보낸다.
 *
 * 같은 잡을 두 번 부르는 일은 세 겹으로 막는다: 이 서버의 `dispatched_at` 창, 임베더의 갤러리 advisory lock,
 * 단계 상태를 쓰는 Lambda의 claim CAS. 어느 하나가 뚫려도 두 번째 호출은 아무것도 하지 않고 끝난다.
 */
@Service
class AnalysisOrchestrator(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoRepository: PhotoRepository,
    private val stageInvoker: StageInvoker,
    private val properties: AnalysisProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
    private val completionNotifications: AnalysisCompletionNotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 새 트랜잭션이다. [dispatch]는 요청 트랜잭션의 afterCommit에서 불리는데, 그 시점에는 커밋된 트랜잭션의 자원이
     * 아직 스레드에 묶여 있어 REQUIRED로 열면 거기에 "참여"하고 갱신이 어디에도 커밋되지 않는다.
     */
    private val tx = TransactionTemplate(transactionTemplate.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 요청이 커밋된 직후. 첫 단계를 보낸다 — 스윕이 30초 뒤 같은 판단을 내리므로 놓쳐도 늦어질 뿐이다. */
    fun dispatch(jobId: Long) {
        val call = try {
            tx.execute { analysisJobRepository.findById(jobId).orElse(null)?.let { step(it) } }
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.debug("분석 잡 {} 은 스윕이 먼저 집었다", jobId)
            null
        }
        call?.let(::invoke)
    }

    /** 살아 있는 잡 전부를 한 걸음씩. 잡마다 트랜잭션을 따로 열어 하나의 실패가 나머지를 막지 않게 한다. */
    fun sweep() {
        val jobIds = tx.execute {
            analysisJobRepository.findAllByStatusInOrderByIdAsc(AnalysisStatus.ACTIVE).map { it.requiredId }
        }!!
        jobIds.forEach { jobId ->
            val call = try {
                tx.execute { analysisJobRepository.findById(jobId).orElse(null)?.let { step(it) } }
            } catch (e: ObjectOptimisticLockingFailureException) {
                log.debug("분석 잡 {} 은 다른 경로가 먼저 갱신했다 — 다음 스윕에서 다시 본다", jobId)
                null
            }
            call?.let(::invoke)
        }
        // 방금 끝난 잡과 옛 Lambda가 status만 DONE으로 닫은 잡을 함께 처리한다.
        completionNotifications.sweep()
    }

    /**
     * 잡 하나를 지금 상태에서 갈 수 있는 데까지 옮긴다. 단계가 끝나면 다음 단계로 넘어가 그 자리에서 보내고,
     * 잡이 끝나면 닫는다. 돌려주는 값은 "트랜잭션이 끝난 뒤 부를 것" 하나 — 걸음마다 EVENT는 최대 하나다.
     */
    private fun step(job: AnalysisJob): StageDispatchDto? {
        if (!job.status.isActive) return null
        val now = ZonedDateTime.now(clock)

        var transitions = 0
        while (transitions++ < MAX_TRANSITIONS) {
            // V4 이전에 만들어져 단계가 없는 잡은 AI 쪽이 닫는다 — 이 서버는 손대지 않는다.
            val stage = job.stage ?: return null
            when (job.stageStatus) {
                AnalysisStatus.DONE -> {
                    val next = job.mode.next(stage)
                    if (next == null) {
                        job.finish(now)
                        log.info("AI 분석 완료: jobId={}, galleryId={}, mode={}", job.requiredId, job.galleryId, job.mode)
                        return null
                    }
                    job.advance(next)
                }
                AnalysisStatus.FAILED -> {
                    job.fail(job.error ?: "$stage 단계가 실패했습니다", now)
                    log.warn("AI 분석 실패: jobId={}, stage={}, error={}", job.requiredId, stage, job.error)
                    return null
                }
                else -> {
                    val outcome = if (stage == AnalysisStage.EMBED) stepEmbed(job, now) else stepLambdaStage(job, stage, now)
                    when (outcome) {
                        is Outcome.Dispatch -> return outcome.call
                        Outcome.Wait -> return null
                        Outcome.Changed -> Unit
                    }
                }
            }
        }
        return null
    }

    /**
     * EMBED는 임베더가 잡을 모르므로 이 서버가 `photo_analysis`를 관측해 단계를 열고 닫는다.
     * 진행(완료 사진 수)이 늘면 살아 있는 것이고, 전부 채워지면 끝이며, 한동안 늘지 않으면 정체다.
     */
    private fun stepEmbed(job: AnalysisJob, now: ZonedDateTime): Outcome {
        val progress = countEmbedded(job)

        if (job.stageStatus == AnalysisStatus.PENDING) {
            return dispatchOrGiveUp(job, AnalysisStage.EMBED, now) { job.openStageByObserver(progress.done.toInt(), now) }
        }
        job.observeProgress(progress.done.toInt(), now)
        if (progress.isComplete) {
            job.completeStage(now)
            return Outcome.Changed
        }
        if (isStalled(job, now)) {
            log.warn("EMBED 정체: jobId={}, galleryId={}, 진행 {}/{}", job.requiredId, job.galleryId, progress.done, progress.targets)
            job.requeueStage()
            return Outcome.Changed
        }
        return Outcome.Wait
    }

    private fun countEmbedded(job: AnalysisJob): EmbedProgress {
        val targets = photoRepository.countByGalleryIdAndStatusNot(job.galleryId, PhotoStatus.PENDING)
        val dispatchedAt = job.dispatchedAt
        // force는 이미 있던 벡터를 다시 쓰므로 "벡터가 있다"로는 진행을 알 수 없다 — 보낸 시각 이후 갱신된 행만 센다.
        val done = if (job.force && dispatchedAt != null) {
            photoRepository.countByGalleryIdAndStatusNotAndEmbeddedSince(job.galleryId, PhotoStatus.PENDING, dispatchedAt)
        } else {
            targets - photoRepository.countByGalleryIdAndStatusNotAndNotEmbedded(job.galleryId, PhotoStatus.PENDING)
        }
        return EmbedProgress(targets = targets, done = done)
    }

    /**
     * SCORE·CATEGORIZE. 단계 상태를 쓰는 Lambda([AnalysisProperties.lambdaReportsStage])면 `stage_status`로 집힘·정체를 보고
     * 안 집히면 다시 보낸다. 옛 Lambda는 단계 상태를 쓰지 않아 집힘을 알 수 없으므로 한 번 보낸 뒤 손을 뗀다 — 재전송·정체
     * 감지 없이 AI 쪽이 `status`를 DONE·FAILED로 닫을 때까지 기다린다(다시 보내면 같은 갤러리를 두 번 돌린다).
     */
    private fun stepLambdaStage(job: AnalysisJob, stage: AnalysisStage, now: ZonedDateTime): Outcome {
        if (!properties.lambdaReportsStage) {
            return if (job.dispatchedAt == null) dispatchOrGiveUp(job, stage, now) {} else Outcome.Wait
        }
        if (job.isStageClaimed) {
            if (isStalled(job, now)) {
                log.warn("{} 정체: jobId={}, galleryId={}", stage, job.requiredId, job.galleryId)
                job.requeueStage()
                return Outcome.Changed
            }
            return Outcome.Wait
        }

        val dispatchedAt = job.dispatchedAt
        if (dispatchedAt != null && dispatchedAt.plus(properties.dispatchRetryAfter).isAfter(now)) return Outcome.Wait
        return dispatchOrGiveUp(job, stage, now) {}
    }

    private fun isStalled(job: AnalysisJob, now: ZonedDateTime): Boolean {
        val last = job.heartbeatAt ?: job.dispatchedAt ?: return false
        return last.plus(properties.stallAfter).isBefore(now)
    }

    private fun dispatchOrGiveUp(job: AnalysisJob, stage: AnalysisStage, now: ZonedDateTime, onDispatch: () -> Unit): Outcome {
        if (job.stageAttempts >= properties.maxAttempts) {
            job.fail("$stage 단계를 ${properties.maxAttempts}회 시도했지만 끝나지 않았습니다", now)
            log.warn("AI 분석 포기: jobId={}, stage={}, attempts={}", job.requiredId, stage, job.stageAttempts)
            return Outcome.Wait
        }
        job.dispatch(now)
        onDispatch()
        return Outcome.Dispatch(StageDispatchDto(jobId = job.requiredId, galleryId = job.galleryId, stage = stage, force = job.force))
    }

    private fun invoke(call: StageDispatchDto) {
        try {
            stageInvoker.invoke(call.stage, call.jobId, call.galleryId, call.force)
            log.info("AI 분석 단계 호출: jobId={}, galleryId={}, stage={}, force={}", call.jobId, call.galleryId, call.stage, call.force)
        } catch (e: AnalysisException) {
            // 호출 실패는 잡을 닫지 않는다 — 시각을 지워 다음 스윕이 기다리지 않고 다시 보내게 한다. 상한은 시도 수가 지킨다.
            log.warn("AI 분석 단계 호출 실패 — 스윕이 다시 보낸다: jobId={}, stage={}, code={}", call.jobId, call.stage, e.errorCode.code)
            tx.executeWithoutResult {
                analysisJobRepository.findById(call.jobId).ifPresent { it.dispatchFailed() }
            }
        }
    }

    private data class EmbedProgress(val targets: Long, val done: Long) {
        val isComplete: Boolean
            get() = done >= targets
    }

    private sealed interface Outcome {
        data class Dispatch(val call: StageDispatchDto) : Outcome
        data object Wait : Outcome
        data object Changed : Outcome
    }

    companion object {
        /** 한 걸음에서 지날 수 있는 전이 수의 상한. 단계 수(3)에 완료→다음 단계 전이를 더한 것보다 넉넉하다. */
        private const val MAX_TRANSITIONS = 8
    }
}
