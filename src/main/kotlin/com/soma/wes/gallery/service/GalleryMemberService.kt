package com.soma.wes.gallery.service

import com.soma.wes.gallery.dto.response.GalleryMemberResponse
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireByIdAndGalleryId
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceRepository
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class GalleryMemberService(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val userRepository: UserRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val workspaceRepository: WorkspaceRepository,
) {

    /**
     * 갤러리에 들어와 있는 사람들. 작가와 부부 모두 본다.
     */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<GalleryMemberResponse> {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
        if (workspace?.type == WorkspaceType.PERSONAL) {
            val members = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId)
            val users = userRepository.findAllById(members.map { it.userId }).associateBy { it.requiredId }
            return members.mapNotNull { member -> users[member.userId]?.let { GalleryMemberResponse.personal(member, it) } }
                .sortedBy { it.role != com.soma.wes.gallery.dto.response.GalleryParticipantRole.OWNER }
        }

        val members = galleryMemberRepository.findAllByGalleryId(galleryId)
        val usersById = userRepository.findAllById(members.map { it.userId })
            .associateBy { it.requiredId }

        return members.mapNotNull { member ->
            usersById[member.userId]?.let { GalleryMemberResponse.of(member, it) }
        }
    }

    /**
     * 멤버를 내보낸다. 담당 작가만 할 수 있다.
     */
    @Transactional
    fun remove(galleryId: Long, memberId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        // 정원을 바꾸는 경로는 전부 갤러리 행을 잠그고 시작한다.
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
        if (workspace?.type == WorkspaceType.PERSONAL) {
            workspaceRepository.findWithLockById(gallery.workspaceId)
            val target = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId)
                .find { it.requiredId == memberId } ?: throw GalleryException(GalleryErrorCode.MEMBER_NOT_FOUND)
            if (target.role == WorkspaceRole.OWNER) throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            workspaceMemberRepository.delete(target)
            notificationPublisher.publish(
                userIds = listOf(target.userId),
                type = UserNotificationType.MEMBERSHIP_REMOVED,
                scope = UserNotificationScope.GALLERY,
                scopeId = galleryId,
                title = "갤러리 소속이 해제되었습니다",
                message = "개설자가 파트너 멤버십을 해제했습니다.",
            )
            return
        }

        val member = galleryMemberRepository.requireByIdAndGalleryId(memberId, galleryId)
        galleryMemberRepository.delete(member)
        notificationPublisher.publish(
            userIds = listOf(member.userId),
            type = UserNotificationType.MEMBERSHIP_REMOVED,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "갤러리 소속이 해제되었습니다",
            message = "작가가 갤러리 멤버십을 해제했습니다.",
        )
    }

    @Transactional
    fun leave(galleryId: Long, userId: Long) {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
        if (workspace?.type == WorkspaceType.PERSONAL) {
            workspaceRepository.findWithLockById(gallery.workspaceId)
            val target = workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, userId)
                ?: throw GalleryException(GalleryErrorCode.MEMBER_NOT_FOUND)
            if (target.role == WorkspaceRole.OWNER) throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            workspaceMemberRepository.delete(target)
            notifyLeave(galleryId, gallery.workspaceId, gallery.title)
            return
        }
        val member = galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw com.soma.wes.gallery.exception.GalleryException(
                com.soma.wes.gallery.exception.GalleryErrorCode.MEMBER_NOT_FOUND,
            )
        galleryMemberRepository.delete(member)
        notifyLeave(galleryId, gallery.workspaceId, gallery.title)
    }

    private fun notifyLeave(galleryId: Long, workspaceId: Long, title: String) {
        val owners = workspaceMemberRepository.findAllByWorkspaceId(workspaceId)
            .filter { it.role == WorkspaceRole.OWNER }
            .map { it.userId }
        notificationPublisher.publish(
            userIds = owners,
            type = UserNotificationType.GALLERY_MEMBER_LEFT,
            scope = UserNotificationScope.GALLERY,
            scopeId = galleryId,
            title = "갤러리 멤버가 나갔습니다",
            message = "${title}에서 고객 한 명이 나갔습니다.",
        )
    }
}
