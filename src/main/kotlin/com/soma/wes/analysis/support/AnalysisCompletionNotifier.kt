package com.soma.wes.analysis.support

import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.springframework.stereotype.Component

/**
 * "AI 폴더가 만들어졌습니다" 알림 — 잡을 DONE으로 닫는 트랜잭션 안에서 부른다. 별도 스윕·마커가 없다: 잡 닫기와
 * 발행이 같은 트랜잭션이고 잡 전이는 `version`으로 한쪽만 이기므로 알림도 한 번이다.
 * 수신자는 작가 워크스페이스의 구성원과 갤러리에 초대된 부부 전원이다.
 */
@Component
class AnalysisCompletionNotifier(
    private val galleries: GalleryRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val publisher: UserNotificationPublisher,
) {

    fun notifyFoldersCreated(galleryId: Long) {
        val gallery = galleries.findById(galleryId).orElse(null) ?: return
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

    companion object {
        const val TITLE = "AI 분석이 완료되었습니다"
        const val MESSAGE = "AI 폴더가 만들어졌습니다. 폴더를 확인해 주세요."
    }
}
