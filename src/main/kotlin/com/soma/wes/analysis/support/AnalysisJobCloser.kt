package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisFailureCode
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.dto.MaterializeOutcomeDto
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 물질화 결과로 CATEGORIZING 잡을 닫고, 폴더가 만들어졌으면 "AI 폴더가 만들어졌습니다"를 알린다.
 *
 * 닫기와 발행이 한 트랜잭션이다 — 닫기는 조건부 UPDATE라 스윕 둘 중 1을 받은 쪽만 발행하고, 발행이 실패하면 닫기도 되돌아가
 * 다음 회차가 다시 닫는다. 그래서 별도 스윕·마커 없이 알림은 정확히 한 번이다. 수신자는 작가 워크스페이스 구성원과 초대된 부부 전원이다.
 */
@Service
class AnalysisJobCloser(
    private val analysisJobRepository: AnalysisJobRepository,
    private val galleries: GalleryRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val publisher: UserNotificationPublisher,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun close(job: AnalysisJob, outcome: MaterializeOutcomeDto) {
        val now = ZonedDateTime.now(clock)
        val closed = when (outcome) {
            is MaterializeOutcomeDto.Created, MaterializeOutcomeDto.NothingNew -> analysisJobRepository.finish(job.requiredId, now)
            is MaterializeOutcomeDto.Failed ->
                analysisJobRepository.fail(job.requiredId, AnalysisJob.trimError(outcome.error), AnalysisFailureCode.FOLDER_FAILED, now)
        }
        if (closed == 0) {
            log.debug("분석 잡 {} 은 다른 스윕이 먼저 닫았다", job.requiredId)
            return
        }

        when (outcome) {
            is MaterializeOutcomeDto.Created -> {
                notifyFoldersCreated(job.galleryId)
                log.info(
                    "event=job.transition job={} gallery={} from=CATEGORIZING to=DONE folders={} details={} assigned={} elapsed={}s total={}s",
                    job.requiredId, job.galleryId, outcome.folders, outcome.details, outcome.assigned,
                    secondsSince(job.dispatchedAt, now), secondsSince(job.createdAt, now),
                )
            }
            MaterializeOutcomeDto.NothingNew -> log.info(
                "event=job.transition job={} gallery={} from=CATEGORIZING to=DONE folders=0 details=0 assigned=0 elapsed={}s total={}s",
                job.requiredId, job.galleryId, secondsSince(job.dispatchedAt, now), secondsSince(job.createdAt, now),
            )
            is MaterializeOutcomeDto.Failed -> log.warn(
                "event=job.transition job={} gallery={} from=CATEGORIZING to=FAILED errorCode={} attempts={} error=\"{}\"",
                job.requiredId, job.galleryId, AnalysisFailureCode.FOLDER_FAILED, job.attempts, outcome.error,
            )
        }
    }

    private fun notifyFoldersCreated(galleryId: Long) {
        val gallery = galleries.findByIdOrNull(galleryId) ?: return
        val recipients = (
            workspaceMembers.findAllByWorkspaceId(gallery.workspaceId).map { it.userId } +
                galleryMembers.findAllByGalleryId(gallery.requiredId).map { it.userId }
            ).distinct()

        publisher.publish(
            userIds = recipients,
            type = UserNotificationType.ANALYSIS_COMPLETED,
            scope = UserNotificationScope.GALLERY,
            scopeId = gallery.requiredId,
            title = TITLE,
            message = MESSAGE,
        )
    }

    private fun secondsSince(from: ZonedDateTime?, now: ZonedDateTime): Long =
        from?.let { Duration.between(it, now).seconds } ?: 0

    companion object {
        const val TITLE = "AI 분석이 완료되었습니다"
        const val MESSAGE = "AI 폴더가 만들어졌습니다. 폴더를 확인해 주세요."
    }
}
