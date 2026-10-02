package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.dto.MaterializeOutcomeDto
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.repository.ConceptAssignmentRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.global.exception.BusinessException
import com.soma.wes.global.logging.LogContext
import com.soma.wes.photo.repository.PhotoPipelineRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 파이프라인 4단계: CATEGORIZING 잡의 categorize 결과를 기다려 AI 폴더로 만들고 잡을 닫는다. 위에서부터 처음 맞는 하나만 한다.
 * 1. categorize Lambda가 `error`를 남겼다 → FAILED
 * 2. 배정 행이 있고 보낸 시각까지 올라온 사진 전부에 백분위가 있다 → 폴더 물질화 → [AnalysisJobCloser]가 닫고 알린다
 * 3. CATEGORIZING 에 들어간 지 [AnalysisProperties.categorizingDeadline]이 지났다 → FAILED
 * 4. 보낸 지 [AnalysisProperties.categorizeTimeout] 안이다 → 기다린다
 * 5. 시도가 [AnalysisProperties.categorizeMaxAttempts]에 닿았다 → FAILED
 * 6. 결과가 늦다 → categorize를 다시 보낸다
 *
 * 재전송이 여기 있는 이유: "결과가 왔나"를 아는 단계만 "늦었나"를 판단할 수 있다. [CategorizeStep]에 두면 결과가 막 도착한 잡에
 * categorize를 한 번 더 보낼 수 있다. 물질화는 folder 도메인의 자기 트랜잭션(갤러리 락)이고, 두 스윕이 겹치면 두 번째는
 * 이미 있는 세트를 돌려받고 닫기에서 0을 받아 알림을 보내지 않는다.
 */
@Component
class FolderStep(
    private val analysisJobRepository: AnalysisJobRepository,
    private val photoPipelineRepository: PhotoPipelineRepository,
    private val conceptAssignmentRepository: ConceptAssignmentRepository,
    private val aiTaskSender: AiTaskSender,
    private val aiFolderMaterializer: AiFolderMaterializer,
    private val jobCloser: AnalysisJobCloser,
    private val properties: AnalysisProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun advance() {
        for (job in analysisJobRepository.findAllByStatusOrderByIdAsc(AnalysisStatus.CATEGORIZING)) {
            try {
                LogContext.gallery(job.galleryId, job.requiredId) { advance(job) }
            } catch (e: RuntimeException) {
                log.error("분석 잡 {} folder 단계 실패 — 다음 회차에 다시 본다", job.requiredId, e)
            }
        }
    }

    private fun advance(job: AnalysisJob) {
        val now = ZonedDateTime.now(clock)

        val error = job.error
        if (error != null) {
            fail(job, AnalysisFailureCode.CATEGORIZE_FAILED, error, now)
            return
        }

        if (isCategorized(job)) {
            val outcome = materialize(job, now) ?: return
            jobCloser.close(job, outcome)
            return
        }

        val categorizingAt = job.categorizingAt
        if (categorizingAt != null && categorizingAt.plus(properties.categorizingDeadline).isBefore(now)) {
            fail(job, AnalysisFailureCode.CATEGORIZE_TIMEOUT, "categorize 가 ${properties.categorizingDeadline.toMinutes()}분 안에 끝나지 않았습니다", now)
            return
        }
        val dispatchedAt = job.dispatchedAt
        if (dispatchedAt != null && dispatchedAt.plus(properties.categorizeTimeout).isAfter(now)) return
        if (job.attempts >= properties.categorizeMaxAttempts) {
            fail(job, AnalysisFailureCode.CATEGORIZE_TIMEOUT, "categorize 를 ${properties.categorizeMaxAttempts}회 시도했지만 끝나지 않았습니다", now)
            return
        }
        redispatch(job, now)
    }

    private fun fail(job: AnalysisJob, code: AnalysisFailureCode, error: String, now: ZonedDateTime) {
        if (analysisJobRepository.fail(job.requiredId, AnalysisJob.trimError(error), code, now) == 0) return
        log.warn(
            "event=job.transition job={} gallery={} from=CATEGORIZING to=FAILED errorCode={} attempts={} error=\"{}\"",
            job.requiredId, job.galleryId, code, job.attempts, error,
        )
    }

    /**
     * 이 잡의 배정 행이 있고(categorize 는 배정을 맨 마지막에 쓴다), categorize 를 보낸 시각까지 올라온 사진 전부에 백분위가 있다.
     * 보낸 뒤에 올라온 사진은 기다리지 않는다 — 이 잡은 닫히고 그 사진은 다음 잡이 분류한다.
     */
    private fun isCategorized(job: AnalysisJob): Boolean {
        val dispatchedAt = job.dispatchedAt ?: return false
        return conceptAssignmentRepository.existsByJobId(job.requiredId) &&
            photoPipelineRepository.isCategorizedAsOf(job.galleryId, dispatchedAt)
    }

    /**
     * 규칙 위반(갤러리 없음·배정 없음 등)은 다시 돌려도 같으므로 잡을 닫는 결과로 바꾼다. "새로 넣을 사진 없음"은 할 일이 없는 것이다.
     * 예상 밖 예외는 횟수를 세고 null 을 돌려준다 — 다음 회차가 다시 만들어 보고, [AnalysisProperties.materializeMaxAttempts]에서 닫는다.
     */
    private fun materialize(job: AnalysisJob, now: ZonedDateTime): MaterializeOutcomeDto? = try {
        val folders = aiFolderMaterializer.materialize(job.galleryId)
        MaterializeOutcomeDto.Created(
            folders = folders.size,
            details = folders.sumOf { it.details.size },
            assigned = folders.sumOf { concept -> concept.details.sumOf { it.photoIds.size } },
        )
    } catch (e: BusinessException) {
        if (e is FolderException && e.errorCode == FolderErrorCode.NO_PHOTOS_TO_ORGANIZE) {
            MaterializeOutcomeDto.NothingNew
        } else {
            MaterializeOutcomeDto.Failed(e.errorCode.message)
        }
    } catch (e: RuntimeException) {
        analysisJobRepository.countMaterializeFailure(job.requiredId, now)
        val attempts = job.materializeAttempts + 1
        log.error("분석 잡 {} 폴더 만들기 실패 {}회 — 상한 {}회", job.requiredId, attempts, properties.materializeMaxAttempts, e)
        if (attempts >= properties.materializeMaxAttempts) {
            MaterializeOutcomeDto.Failed("폴더 만들기가 ${attempts}회 실패했습니다: ${e::class.simpleName}")
        } else {
            null
        }
    }

    private fun redispatch(job: AnalysisJob, now: ZonedDateTime) {
        val claimed = analysisJobRepository.redispatchCategorize(
            id = job.requiredId,
            now = now,
            dispatchedBefore = now.minus(properties.categorizeTimeout),
            maxAttempts = properties.categorizeMaxAttempts,
        )
        if (claimed == 0) return

        log.warn("analysis job={} gallery={} categorize redispatch attempts={}", job.requiredId, job.galleryId, job.attempts + 1)
        try {
            aiTaskSender.send(AiTaskDto.Categorize(galleryId = job.galleryId, jobId = job.requiredId, conceptCount = job.conceptCount))
        } catch (e: AnalysisException) {
            log.warn("categorize 재전송 실패 — 다음 회차에 다시 보낸다: job={} code={}", job.requiredId, e.errorCode.code)
            analysisJobRepository.clearDispatchedAt(job.requiredId, now)
        }
    }
}
