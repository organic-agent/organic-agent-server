package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.domain.AnalysisStatus
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** 분석 상태와 독립된 DB 스윕이라 status만 DONE으로 바꾸는 옛 Lambda도 같은 완료 알림을 보낸다. */
@Service
class AnalysisCompletionNotificationService(
    private val jobs: AnalysisJobRepository,
    private val galleries: GalleryRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val publisher: UserNotificationPublisher,
    transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionTemplate.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** 한 스윕은 최대 100건. 발행 실패는 마커도 롤백되어 다음 스윕에서 다시 처리한다. */
    fun sweep() {
        val jobIds = tx.execute { jobs.findAwaitingCompletionNotification(PageRequest.of(0, BATCH_SIZE)) }!!
        jobIds.forEach { jobId ->
            try {
                publishCompleted(jobId)
            } catch (e: RuntimeException) {
                log.warn("AI 분석 완료 알림 실패 — 다음 스윕에서 다시 처리한다: jobId={}", jobId, e)
            }
        }
    }

    /** 잡 잠금, 수신자 알림, 마커 저장을 한 트랜잭션으로 묶어 여러 서버의 스윕도 한 번만 발행한다. */
    fun publishCompleted(jobId: Long) {
        tx.executeWithoutResult {
            val job = jobs.findWithLockById(jobId) ?: return@executeWithoutResult
            if (job.status != AnalysisStatus.DONE || job.completionNotifiedAt != null) return@executeWithoutResult
            val gallery = galleries.findById(job.galleryId).orElse(null) ?: return@executeWithoutResult
            val recipients = (workspaceMembers.findAllByWorkspaceId(gallery.workspaceId).map { it.userId } +
                galleryMembers.findAllByGalleryId(gallery.requiredId).map { it.userId }).distinct()
            val message = when (job.mode) {
                AnalysisMode.FULL -> "AI 사진 분석이 완료되었습니다. 분석 결과를 확인해 주세요."
                AnalysisMode.NAMING -> "AI 컨셉 분류가 완료되었습니다. 분류 결과를 확인해 주세요."
            }
            publisher.publish(
                userIds = recipients,
                type = UserNotificationType.ANALYSIS_COMPLETED,
                scope = UserNotificationScope.GALLERY,
                scopeId = gallery.requiredId,
                title = "AI 분석이 완료되었습니다",
                message = message,
            )
            job.markCompletionNotified(ZonedDateTime.now(clock))
        }
    }

    companion object {
        const val BATCH_SIZE = 100
    }
}
