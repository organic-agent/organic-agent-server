package com.soma.wes.admin.resource.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.repository.AdminWorkflowExecutionRepository
import com.soma.wes.admin.resource.repository.AdminWorkflowExecutionRepository.ProcessingExecutionJob
import com.soma.wes.admin.resource.repository.WorkflowExecutionException
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.service.port.AiTaskSender
import com.soma.wes.global.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** PENDING 작업을 실제 실행 경계로 전달하며, 미구성 작업을 성공으로 위장하지 않는다. */
@Component
class AdminWorkflowExecutor(
    private val repository: AdminWorkflowExecutionRepository,
    private val properties: AdminWorkflowExecutorProperties,
    private val aiTaskSender: AiTaskSender,
    private val auditService: AdminAuditService,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.admin.workflow-executor.fixed-delay:10s}")
    fun scheduledRun() {
        if (!properties.enabled) return
        runOnce()
    }

    /** 테스트와 운영자 진단에서 스케줄 활성화 여부와 무관하게 한 batch만 명시 실행한다. */
    fun runOnce() {
        reapStaleExactPhotoJobs()
        repository.claimProcessingJobs(
            limit = properties.batchSize,
            maxAttempts = properties.maxAttempts,
            retryDelay = properties.retryDelay,
            staleTimeout = properties.staleTimeout,
        ).forEach(::executeProcessingJob)
    }

    private fun executeProcessingJob(job: ProcessingExecutionJob) {
        if (!repository.targetActive(job.targetType, job.targetId)) {
            cancelDeletedTarget(job)
            return
        }
        if (job.attemptCount >= properties.maxAttempts.coerceAtLeast(1)) {
            failAttemptsExhausted(job)
            return
        }
        when (job.jobType) {
            "DERIVATIVE", "EMBEDDING" -> executeExactPhotoProcessing(job)
            else -> failBeforeSend(job, "UNKNOWN_JOB_TYPE")
        }
    }

    private fun executeExactPhotoProcessing(job: ProcessingExecutionJob) {
        val requestSeed = try {
            if (!aiTaskSender.isAvailable(AiTaskDto.ExactPhoto::class)) throw WorkflowExecutionException("PHOTO_PROCESSING_NOT_CONFIGURED")
            if (job.targetType != AdminResourceType.PHOTO) throw WorkflowExecutionException("PHOTO_TARGET_REQUIRED")
            ExactPhotoRequestSeed(
                galleryId = job.payload.long("galleryId")
                    ?: throw WorkflowExecutionException("GALLERY_ID_REQUIRED"),
                storageKey = job.payload.string("storageKey")
                    ?.takeIf(String::isNotBlank)
                    ?: throw WorkflowExecutionException("STORAGE_KEY_REQUIRED"),
                revisionId = job.revisionId ?: throw WorkflowExecutionException("REVISION_ID_REQUIRED"),
            )
        } catch (error: Exception) {
            failBeforeSend(job, failureCode(error))
            return
        }

        val startedAttempt = repository.markProcessingExecutionStarted(
            jobId = job.id,
            attemptCount = job.attemptCount,
            targetType = job.targetType,
            targetId = job.targetId,
        )
        if (startedAttempt == null) {
            // 대상 삭제면 CLAIMED_NOT_SENT를 CANCELED로 정리한다. 취소/새 claim이 먼저였다면
            // attempt CAS가 0이므로 그 상태를 건드리지 않는다.
            cancelDeletedTarget(job)
            log.info("외부 dispatch 직전 claim 또는 대상 검증에 실패해 호출하지 않음: jobId={}", job.id)
            return
        }
        val startedJob = job.copy(attemptCount = startedAttempt)

        try {
            aiTaskSender.send(
                AiTaskDto.ExactPhoto(
                    jobId = startedJob.id,
                    attemptCount = startedJob.attemptCount,
                    jobType = startedJob.jobType,
                    photoId = startedJob.targetId,
                    galleryId = requestSeed.galleryId,
                    storageKey = requestSeed.storageKey,
                    revisionId = requestSeed.revisionId,
                ),
            )
        } catch (error: Exception) {
            // invoke가 예외를 던져도 상대가 요청을 받았는지는 알 수 없다. FAILED로 바꾸거나
            // stale reclaim하면 중복 외부 호출이 될 수 있으므로 운영자 확인이 필요한 상태로 둔다.
            markDispatchOutcomeUnknown(startedJob, error)
            return
        }

        try {
            transactionTemplate.executeWithoutResult {
                requireClaim(
                    repository.markProcessingJobDispatched(startedJob.id, startedJob.attemptCount),
                    startedJob,
                )
                auditService.recordEvent(
                    action = AdminAuditAction.REPROCESS_DISPATCHED,
                    outcome = AdminAuditOutcome.SUCCESS,
                    actorAdminId = job.actorAdminId,
                    targetType = job.targetType.auditTargetType,
                    targetId = job.targetId.toString(),
                    targetLabel = null,
                    reason = job.reason,
                    sourceAddress = null,
                    changedFields = listOf("jobStatus"),
                )
            }
        } catch (error: ProcessingClaimLostException) {
            // cascade 취소가 terminal CAS를 이겨도 외부 수락 자체는 이미 일어난 사실이다.
            // CANCELED 행은 덮어쓰지 않고 그 사실만 별도 트랜잭션의 영구 감사로 남긴다.
            recordDispatchAcceptedAfterClaimLoss(startedJob)
        } catch (error: Exception) {
            // 외부 호출은 성공했지만 DB 상태와 감사의 원자 finalize에 실패했다.
            markDispatchOutcomeUnknown(startedJob, error)
        }
    }

    private fun reapStaleExactPhotoJobs() {
        try {
            val timedOut = transactionTemplate.execute {
                repository.markStaleExactPhotoJobsFailed(
                    limit = properties.batchSize,
                    dispatchedTimeout = properties.dispatchedTimeout,
                ).also { jobs ->
                    jobs.forEach { job ->
                        auditService.recordEvent(
                            action = AdminAuditAction.REPROCESS_DISPATCH_FAILED,
                            outcome = AdminAuditOutcome.FAILURE,
                            actorAdminId = job.actorAdminId,
                            targetType = job.targetType.auditTargetType,
                            targetId = job.targetId.toString(),
                            targetLabel = null,
                            reason = job.reason,
                            sourceAddress = null,
                            changedFields = listOf("jobStatus", "failureCode"),
                        )
                    }
                }
            }.orEmpty()
            timedOut.forEach { job ->
                log.warn(
                    "관리자 외부 처리 결과 timeout: jobId={}, type={}, attempt={}",
                    job.id,
                    job.jobType,
                    job.attemptCount,
                )
            }
        } catch (error: Exception) {
            log.error("stale exact-photo 상태와 실패 감사를 원자 저장하지 못함", error)
        }
    }

    /** 외부 호출 전에 확정된 오류만 FAILED와 실패 감사로 한 트랜잭션에 남긴다. */
    private fun failBeforeSend(job: ProcessingExecutionJob, failureCode: String) {
        try {
            transactionTemplate.executeWithoutResult {
                requireClaim(
                    repository.markProcessingJobFailedBeforeStart(job.id, job.attemptCount, failureCode),
                    job,
                )
                auditService.recordEvent(
                    action = AdminAuditAction.REPROCESS_DISPATCH_FAILED,
                    outcome = AdminAuditOutcome.FAILURE,
                    actorAdminId = job.actorAdminId,
                    targetType = job.targetType.auditTargetType,
                    targetId = job.targetId.toString(),
                    targetLabel = null,
                    reason = job.reason,
                    sourceAddress = null,
                    changedFields = listOf("jobStatus", "failureCode"),
                )
            }
            log.warn("관리자 처리 작업 실행 전 실패: jobId={}, type={}, code={}", job.id, job.jobType, failureCode)
        } catch (error: ProcessingClaimLostException) {
            log.info("관리자 처리 작업 claim이 해제되어 실패 상태를 저장하지 않음: jobId={}", job.id)
        } catch (error: Exception) {
            log.error("관리자 처리 작업 실패 상태와 감사를 원자 저장하지 못함: jobId={}", job.id, error)
        }
    }

    /** 마지막 허용 실행 뒤 복구된 로컬 lease는 attempt를 더 쓰거나 재실행하지 않는다. */
    private fun failAttemptsExhausted(job: ProcessingExecutionJob) {
        try {
            transactionTemplate.executeWithoutResult {
                requireClaim(
                    repository.markProcessingJobAttemptsExhausted(job.id, job.attemptCount),
                    job,
                )
                auditService.recordEvent(
                    action = AdminAuditAction.REPROCESS_DISPATCH_FAILED,
                    outcome = AdminAuditOutcome.FAILURE,
                    actorAdminId = job.actorAdminId,
                    targetType = job.targetType.auditTargetType,
                    targetId = job.targetId.toString(),
                    targetLabel = null,
                    reason = job.reason,
                    sourceAddress = null,
                    changedFields = listOf("jobStatus", "failureCode"),
                )
            }
            log.warn(
                "관리자 처리 작업 최대 attempt 소진: jobId={}, type={}, attempts={}",
                job.id,
                job.jobType,
                job.attemptCount,
            )
        } catch (error: ProcessingClaimLostException) {
            log.info("최대 attempt 처리 전 claim이 해제되어 실패 상태를 저장하지 않음: jobId={}", job.id)
        } catch (error: Exception) {
            log.error("최대 attempt 실패 상태와 감사를 원자 저장하지 못함: jobId={}", job.id, error)
        }
    }

    private fun markDispatchOutcomeUnknown(job: ProcessingExecutionJob, cause: Throwable) {
        try {
            transactionTemplate.executeWithoutResult {
                requireClaim(
                    repository.markProcessingJobAmbiguous(
                        job.id,
                        job.attemptCount,
                        AdminWorkflowExecutionRepository.DISPATCH_OUTCOME_UNKNOWN,
                    ),
                    job,
                )
                auditService.recordEvent(
                    action = AdminAuditAction.REPROCESS_DISPATCH_UNKNOWN,
                    outcome = AdminAuditOutcome.FAILURE,
                    actorAdminId = job.actorAdminId,
                    targetType = job.targetType.auditTargetType,
                    targetId = job.targetId.toString(),
                    targetLabel = null,
                    reason = job.reason,
                    sourceAddress = null,
                    changedFields = listOf("jobStatus", "failureCode"),
                )
            }
        } catch (error: ProcessingClaimLostException) {
            log.info("관리자 처리 작업 claim이 해제되어 불확실 상태를 덮어쓰지 않음: jobId={}", job.id)
            return
        } catch (error: Exception) {
            // 상태와 감사를 함께 rollback한다. 외부 작업의 plain stale DISPATCHING도 claim에서
            // 제외되므로 audit 장애가 자동 중복 호출로 이어지지 않는다.
            log.error("불확실한 dispatch 상태와 감사를 원자 저장하지 못함: jobId={}", job.id, error)
            return
        }
        log.error(
            "관리자 외부 dispatch 결과를 확정할 수 없음: jobId={}, type={}, code={}, causeType={}",
            job.id,
            job.jobType,
            AdminWorkflowExecutionRepository.DISPATCH_OUTCOME_UNKNOWN,
            failureCode(cause),
        )
    }

    private fun recordDispatchAcceptedAfterClaimLoss(job: ProcessingExecutionJob) {
        runCatching {
            transactionTemplate.executeWithoutResult {
                auditService.recordEvent(
                    action = AdminAuditAction.REPROCESS_DISPATCHED,
                    outcome = AdminAuditOutcome.SUCCESS,
                    actorAdminId = job.actorAdminId,
                    targetType = job.targetType.auditTargetType,
                    targetId = job.targetId.toString(),
                    targetLabel = null,
                    reason = job.reason,
                    sourceAddress = null,
                )
            }
        }.onSuccess {
            log.info("취소 경쟁 뒤 외부 dispatch 수락 사실을 별도 감사함: jobId={}", job.id)
        }.onFailure { auditError ->
            log.error("취소 경쟁 뒤 외부 dispatch 수락 감사를 저장하지 못함: jobId={}", job.id, auditError)
        }
    }

    private fun cancelDeletedTarget(job: ProcessingExecutionJob) {
        val canceled = repository.cancelClaimedProcessingJobIfTargetDeleted(
            id = job.id,
            attemptCount = job.attemptCount,
            targetType = job.targetType,
            targetId = job.targetId,
        )
        if (canceled != 1) {
            log.info("삭제 대상 처리 작업의 claim이 이미 해제됐거나 대상이 복원됨: jobId={}", job.id)
        }
    }

    private fun requireClaim(updated: Int, job: ProcessingExecutionJob) {
        if (updated != 1) throw ProcessingClaimLostException(job.id, job.attemptCount)
    }

    private fun failureCode(error: Throwable): String = when (error) {
        is WorkflowExecutionException -> error.failureCode
        is BusinessException -> error.errorCode.code
        else -> error::class.simpleName ?: "EXECUTION_FAILED"
    }.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    private fun Map<String, Any?>.long(name: String): Long? =
        (this[name] as? Number)?.toLong() ?: this[name]?.toString()?.toLongOrNull()

    private fun Map<String, Any?>.string(name: String): String? = this[name] as? String

    private data class ExactPhotoRequestSeed(
        val galleryId: Long,
        val storageKey: String,
        val revisionId: Long,
    )

    private class ProcessingClaimLostException(jobId: Long, attemptCount: Int) :
        RuntimeException("processing claim lost: jobId=$jobId attempt=$attemptCount")

}
