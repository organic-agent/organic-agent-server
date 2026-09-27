package com.soma.wes.analysis.service

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.MaterializeOutcomeDto
import com.soma.wes.analysis.dto.OrchestratorActionDto
import com.soma.wes.analysis.dto.StageCallDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AiConceptAssignmentRepository
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.StageInvoker
import com.soma.wes.analysis.support.AnalysisCompletionNotifier
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.service.AiFolderMaterializeService
import com.soma.wes.global.exception.BusinessException
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 분석 잡의 상태 기계 — ANALYZING → CATEGORIZING → DONE | FAILED. 잡은 Lambda를 세 번 부르지 않는다. 임베더 배정은
 * 잡과 무관하게 [EmbedDispatcher]가 하고, 잡은 `photo_analysis`를 **관측**해 점수가 다 차면 categorize를 한 번 보내고,
 * 배정이 오면 폴더를 물질화한 뒤 닫는다. 요청 직후([dispatch])와 주기 스윕([sweep])이 같은 [step]을 지나므로 별도의
 * 복구 로직이 없다 — 상태가 전부 DB에 있어 스윕 한 번이 곧 기동 복구다.
 *
 * 한 잡의 한 걸음은 짧은 트랜잭션 하나다(관측 + 전이). Lambda 호출과 물질화는 그 트랜잭션 밖이다 — 커넥션을 문 채
 * 네트워크를 기다리지 않고, 물질화(갤러리 락, 수 초~수십 초)는 자기 트랜잭션으로 돈다. 스윕 둘이 같은 잡을 보면
 * `version`(낙관적 잠금)이 한쪽을 버리고 그쪽은 EVENT를 보내지 않는다(ANALYZING→CATEGORIZING CAS).
 */
@Service
class AnalysisOrchestrator(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val aiConceptAssignmentRepository: AiConceptAssignmentRepository,
    private val stageInvoker: StageInvoker,
    private val embedDispatcher: EmbedDispatcher,
    private val scoreWorkerSupervisor: ScoreWorkerSupervisor,
    // [REFACTOR-RENAME 2026-09-27] AiFolderService → AiFolderMaterializeService (클래스 이름만 변경, 동작 동일)
    private val aiFolderMaterializeService: AiFolderMaterializeService,
    private val completionNotifier: AnalysisCompletionNotifier,
    private val properties: AnalysisProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 항상 새 트랜잭션이다. [dispatch]는 요청 트랜잭션의 afterCommit에서 불리는데, 그 시점에는 커밋된 트랜잭션의 자원이
     * 아직 스레드에 묶여 있어 REQUIRED로 열면 거기에 "참여"하고 갱신이 어디에도 커밋되지 않는다.
     */
    private val tx = TransactionTemplate(transactionTemplate.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 요청이 커밋된 직후 한 걸음. 스윕이 5초 뒤 같은 판단을 내리므로 놓쳐도 늦어질 뿐이다. */
    fun dispatch(jobId: Long) {
        stepJob(jobId)
    }

    /** 임베더 배정 → GPU 제어·score 폴백 → 살아 있는 잡 전부 한 걸음씩. 단계마다 실패를 가둬 하나의 실패가 나머지를 막지 않게 한다. */
    fun sweep() {
        try {
            embedDispatcher.dispatch()
        } catch (e: RuntimeException) {
            log.error("embed dispatch 실패 — 잡 걸음은 계속한다", e)
        }
        try {
            scoreWorkerSupervisor.control()
        } catch (e: RuntimeException) {
            log.error("gpu control 실패 — 잡 걸음은 계속한다", e)
        }

        val jobIds = tx.execute {
            analysisJobRepository.findAllByStatusInOrderByIdAsc(AnalysisStatus.ACTIVE).map { it.requiredId }
        }!!
        jobIds.forEach { jobId ->
            try {
                stepJob(jobId)
            } catch (e: RuntimeException) {
                log.error("분석 잡 {} 걸음 실패 — 다음 스윕에서 다시 본다", jobId, e)
            }
        }
    }

    private fun stepJob(jobId: Long) {
        val action = try {
            tx.execute { analysisJobRepository.findById(jobId).orElse(null)?.let { step(it) } }
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.debug("분석 잡 {} 은 다른 경로가 먼저 갱신했다 — 다음 스윕에서 다시 본다", jobId)
            null
        }
        when (action) {
            is OrchestratorActionDto.Invoke -> invoke(action)
            is OrchestratorActionDto.Materialize -> materialize(action)
            null -> Unit
        }
    }

    /** 잡 하나를 지금 상태에서 관측해 옮긴다. 돌려주는 값은 "트랜잭션이 끝난 뒤 할 일" 하나다. */
    private fun step(job: AnalysisJob): OrchestratorActionDto? {
        val now = ZonedDateTime.now(clock)
        return when (job.status) {
            AnalysisStatus.ANALYZING -> stepAnalyzing(job, now)
            AnalysisStatus.CATEGORIZING -> stepCategorizing(job, now)
            AnalysisStatus.DONE, AnalysisStatus.FAILED -> null
        }
    }

    /**
     * 점수가 기대 장수만큼 찼고 아직 올라오는 사진이 없으면 CATEGORIZING으로. 대상이 한 장도 남지 않으면(전부 실패·삭제) 닫는다.
     * 점수를 내는 것(GPU 워커·Lambda 폴백)은 [ScoreWorkerSupervisor]의 일이라 여기서는 기다리기만 한다.
     */
    private fun stepAnalyzing(job: AnalysisJob, now: ZonedDateTime): OrchestratorActionDto? {
        val progress = progressOf(job, now)

        if (progress.livePending == 0L && progress.pending == 0L && progress.expected == 0L) {
            job.fail("분석할 사진이 없습니다(실패 ${progress.failed}장)", now)
            logTransition(job, "ANALYZING->FAILED", progress, now)
            return null
        }
        if (progress.livePending == 0L && progress.isFullyScored) {
            job.startCategorizing(now)
            logTransition(job, "ANALYZING->CATEGORIZING", progress, now)
            return OrchestratorActionDto.Invoke(job.requiredId, StageCallDto.Categorize(galleryId = job.galleryId, jobId = job.requiredId))
        }
        return null
    }

    /**
     * categorize의 결과(배정 행 + 백분위)가 다 왔으면 물질화로. Lambda가 `error`를 남겼으면 FAILED.
     * 보낸 지 오래됐는데 결과가 없으면 다시 보내고, 상한을 넘기면 FAILED.
     */
    private fun stepCategorizing(job: AnalysisJob, now: ZonedDateTime): OrchestratorActionDto? {
        job.error?.let { error ->
            job.fail(error, now)
            log.warn("analysis job={} gallery={} CATEGORIZING->FAILED error={}", job.requiredId, job.galleryId, error)
            return null
        }

        val progress = progressOf(job, now)
        if (aiConceptAssignmentRepository.existsByJobId(job.requiredId) && progress.categorized == progress.expected) {
            return OrchestratorActionDto.Materialize(jobId = job.requiredId, galleryId = job.galleryId)
        }

        val dispatchedAt = job.dispatchedAt
        if (dispatchedAt != null && dispatchedAt.plus(properties.categorizeTimeout).isAfter(now)) return null
        if (job.attempts >= properties.categorizeMaxAttempts) {
            job.fail("categorize 를 ${properties.categorizeMaxAttempts}회 시도했지만 끝나지 않았습니다", now)
            logTransition(job, "CATEGORIZING->FAILED", progress, now)
            return null
        }
        job.redispatchCategorize(now)
        log.warn("analysis job={} gallery={} categorize redispatch attempts={}", job.requiredId, job.galleryId, job.attempts)
        return OrchestratorActionDto.Invoke(job.requiredId, StageCallDto.Categorize(galleryId = job.galleryId, jobId = job.requiredId))
    }

    private fun progressOf(job: AnalysisJob, now: ZonedDateTime): GalleryAnalysisProgress =
        photoPipelineRepository.progressOf(job.galleryId, liveSince = now.minus(properties.uploadQuietAfter))

    private fun invoke(action: OrchestratorActionDto.Invoke) {
        try {
            stageInvoker.invoke(action.call)
        } catch (e: AnalysisException) {
            // 호출 실패는 잡을 닫지 않는다 — 시각을 지워 다음 스윕이 타임아웃을 기다리지 않고 다시 보내게 한다. 상한은 시도 수가 지킨다.
            log.warn("AI 분석 호출 실패 — 스윕이 다시 보낸다: job={} call={} code={}", action.jobId, action.call, e.errorCode.code)
            tx.executeWithoutResult { analysisJobRepository.findById(action.jobId).ifPresent { it.dispatchFailed() } }
        }
    }

    /**
     * 자기 트랜잭션(갤러리 락)으로 폴더를 만들고, 다음 트랜잭션에서 잡을 닫으며 알림을 발행한다. 두 스윕이 겹치면 두 번째
     * 물질화는 이미 있는 세트를 돌려주고 잡은 이미 닫혀 있어 알림도 한 번이다. "새로 넣을 사진 없음"은 할 일이 없는 것이라 DONE.
     */
    private fun materialize(action: OrchestratorActionDto.Materialize) {
        val outcome = try {
            val folders = aiFolderMaterializeService.materializeFromAnalysis(action.galleryId)
            MaterializeOutcomeDto.Created(
                folders = folders.size,
                details = folders.sumOf { it.details.size },
                assigned = folders.sumOf { concept -> concept.details.sumOf { it.photoIds.size } },
            )
        } catch (e: BusinessException) {
            // 갤러리가 사라졌거나(GalleryException) 배정이 없는 등 규칙 위반은 다시 돌려도 같으므로 잡을 닫는다.
            if (e is FolderException && e.errorCode == FolderErrorCode.NO_PHOTOS_TO_ORGANIZE) {
                MaterializeOutcomeDto.NothingNew
            } else {
                MaterializeOutcomeDto.Failed(e.errorCode.message)
            }
        }

        try {
            tx.executeWithoutResult {
                val job = analysisJobRepository.findById(action.jobId).orElse(null) ?: return@executeWithoutResult
                if (job.status != AnalysisStatus.CATEGORIZING) return@executeWithoutResult
                val now = ZonedDateTime.now(clock)
                when (outcome) {
                    is MaterializeOutcomeDto.Created -> {
                        job.finish(now)
                        completionNotifier.notifyFoldersCreated(job.galleryId)
                        log.info(
                            "analysis job={} gallery={} CATEGORIZING->DONE folders={} details={} assigned={} elapsed={}s total={}s",
                            job.requiredId, job.galleryId, outcome.folders, outcome.details, outcome.assigned,
                            secondsSince(job.dispatchedAt, now), secondsSince(job.createdAt, now),
                        )
                    }
                    MaterializeOutcomeDto.NothingNew -> {
                        job.finish(now)
                        log.info(
                            "analysis job={} gallery={} CATEGORIZING->DONE folders=0 details=0 assigned=0 elapsed={}s total={}s",
                            job.requiredId, job.galleryId, secondsSince(job.dispatchedAt, now), secondsSince(job.createdAt, now),
                        )
                    }
                    is MaterializeOutcomeDto.Failed -> {
                        job.fail(outcome.error, now)
                        log.warn("analysis job={} gallery={} CATEGORIZING->FAILED error={}", job.requiredId, job.galleryId, outcome.error)
                    }
                }
            }
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.debug("분석 잡 {} 은 다른 스윕이 먼저 닫았다", action.jobId)
        }
    }

    private fun logTransition(job: AnalysisJob, transition: String, progress: GalleryAnalysisProgress, now: ZonedDateTime) {
        log.info(
            "analysis job={} gallery={} {} expected={} embedded={} scored={} failed={} elapsed={}s",
            job.requiredId, job.galleryId, transition,
            progress.expected, progress.embedded, progress.scored, progress.failed, secondsSince(job.createdAt, now),
        )
    }

    private fun secondsSince(from: ZonedDateTime?, now: ZonedDateTime): Long =
        from?.let { Duration.between(it, now).seconds } ?: 0
}
