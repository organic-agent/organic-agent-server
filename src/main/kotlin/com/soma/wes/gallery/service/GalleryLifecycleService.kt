package com.soma.wes.gallery.service

import com.soma.wes.gallery.config.GalleryLifecycleProperties
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryLifecycleRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.repository.UserNotificationRepository
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GalleryLifecycleService(
    private val galleries: GalleryRepository,
    private val candidates: GalleryLifecycleRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val notifications: UserNotificationRepository,
    private val publisher: UserNotificationPublisher,
    private val properties: GalleryLifecycleProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun nextBatch(afterId: Long): List<Long> {
        val now = ZonedDateTime.now(clock)
        return candidates.findCandidates(afterId, now, now.plusDays(REMINDER_DAYS), PageRequest.of(0, properties.batchSize))
    }

    /** 상태 전이와 알림을 같은 잠금·트랜잭션에 둬 여러 서버의 스윕도 중복 발행하지 않는다. */
    @Transactional
    fun process(galleryId: Long) {
        val gallery = galleries.findWithLockById(galleryId) ?: return
        if (gallery.stage == GalleryStage.ARCHIVED) return
        val workspace = workspaces.findById(gallery.workspaceId).orElse(null) ?: return
        val personal = workspace.type == WorkspaceType.PERSONAL
        val now = ZonedDateTime.now(clock)
        val recipients = (workspaceMembers.findAllByWorkspaceId(gallery.workspaceId).map { it.userId } +
            galleryMembers.findAllByGalleryId(galleryId).map { it.userId }).distinct()
        val expiry = gallery.planExpiresAt
        if (personal && expiry != null && !expiry.isAfter(now)) {
            // 개인 플랜 갤러리는 공개 상태로 생성된다. 과거 비공개 행은 일반 상태 전이 규칙을 우회하지 않는다.
            if (gallery.status == GalleryStatus.DRAFT) return
            gallery.close()
            properties.archivedRetentionDays?.let { gallery.archivedUntil = now.plusDays(it.toLong()) }
            publishOnce(
                gallery, recipients, UserNotificationType.PLAN_EXPIRED,
                "이용 기간이 종료되었습니다", "이용 기간이 끝나 갤러리가 보관 상태로 전환되었습니다.",
            )
            return
        }
        gallery.selectionDeadline?.takeIf {
            gallery.stage == GalleryStage.SELECTION_IN_PROGRESS && it.isAfter(now) && !it.isAfter(now.plusDays(REMINDER_DAYS))
        }?.let { deadline ->
            if (!personal) {
                publishOnce(
                    gallery, recipients, UserNotificationType.DEADLINE_REMINDER,
                    "선택 마감이 다가옵니다", "사진 선택이 ${deadline.format(DISPLAY_TIME)}에 마감됩니다.",
                )
            } else if (expiry == null || deadline.isBefore(expiry)) {
                // 개인 갤러리의 목표일은 고르기를 막지 않는다. 목표일을 비우면 이용 기간 만료일이 들어가므로,
                // 그때는 아래 이용 기간 알림 하나만 보낸다.
                publishOnce(
                    gallery, recipients, UserNotificationType.DEADLINE_REMINDER,
                    "목표일이 다가옵니다", "정해 둔 목표일은 ${deadline.format(DISPLAY_TIME)}입니다. 목표일이 지나도 사진은 계속 고를 수 있어요.",
                )
            }
        }
        if (personal) expiry?.takeIf { it.isAfter(now) && !it.isAfter(now.plusDays(REMINDER_DAYS)) }?.let { deadline ->
            publishOnce(
                gallery, recipients, UserNotificationType.PLAN_EXPIRY_REMINDER,
                "이용 기간이 곧 종료됩니다", "현재 플랜의 이용 기간이 ${deadline.format(DISPLAY_TIME)}에 종료됩니다.",
            )
        }
    }

    private fun publishOnce(
        gallery: Gallery,
        recipients: List<Long>,
        type: UserNotificationType,
        title: String,
        message: String,
    ) {
        val notified = notifications.findRecipientIds(type, UserNotificationScope.GALLERY, gallery.requiredId, message).toSet()
        val pending = recipients.filterNot { it in notified }
        if (pending.isEmpty()) return
        publisher.publish(
            userIds = pending,
            type = type,
            scope = UserNotificationScope.GALLERY,
            scopeId = gallery.requiredId,
            title = title,
            message = message,
        )
    }

    companion object {
        /** 선택 마감(개인 갤러리는 목표일)과 개인 플랜 만료를 3일 전에 안내한다. */
        const val REMINDER_DAYS = 3L
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
