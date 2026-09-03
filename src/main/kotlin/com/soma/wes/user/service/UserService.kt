package com.soma.wes.user.service

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.dto.request.UpdateUserRequest
import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.dto.response.UserWorkspaceKind
import com.soma.wes.user.dto.response.UserWorkspaceResponse
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val authTokenProvider: AuthTokenProvider,
) {

    @Transactional(readOnly = true)
    fun getUser(id: Long): UserResponse =
        UserResponse.from(userRepository.requireById(id))

    @Transactional
    fun update(id: Long, request: UpdateUserRequest): UserResponse {
        val user = userRepository.requireById(id)
        user.updateNickname(request.nickname.trim())
        return UserResponse.from(user)
    }

    @Transactional(readOnly = true)
    fun listWorkspaces(userId: Long): List<UserWorkspaceResponse> {
        val memberships = workspaceMemberRepository.findAllByUserId(userId)
        val workspaces = workspaceRepository.findAllById(memberships.map { it.workspaceId })
            .associateBy { it.requiredId }
        val studios = studioRepository.findAllByIdInAndSuspendedAtIsNull(
            workspaces.values.filter { it.type == WorkspaceType.STUDIO }.map { it.requiredId },
        ).associateBy { it.workspaceId }
        val galleryMemberships = galleryMemberRepository.findAllByUserId(userId)
        val personalWorkspaceIds = memberships.mapNotNull { membership ->
            workspaces[membership.workspaceId]
                ?.takeIf { it.type == WorkspaceType.PERSONAL }
                ?.requiredId
        }
        val personalGalleries = galleryRepository.findAllByStudioIdIn(personalWorkspaceIds)
        val memberGalleries = galleryRepository.findAllById(galleryMemberships.map { it.galleryId })
        val galleries = (personalGalleries + memberGalleries).associateBy { it.requiredId }

        val studioRows = memberships.mapNotNull { membership ->
            val workspace = workspaces[membership.workspaceId]
                ?.takeIf { it.type == WorkspaceType.STUDIO } ?: return@mapNotNull null
            val studio = studios[workspace.requiredId] ?: return@mapNotNull null
            val latestGalleryAt = galleryRepository.findAllByStudioId(workspace.requiredId)
                .mapNotNull { it.updatedAt ?: it.createdAt }
                .maxOrNull()
            UserWorkspaceResponse(
                id = workspace.requiredId,
                kind = UserWorkspaceKind.STUDIO,
                workspaceId = workspace.requiredId,
                galleryId = null,
                name = studio.name,
                role = membership.role,
                lastActivityAt = listOfNotNull(studio.updatedAt, studio.createdAt, latestGalleryAt).maxOrNull(),
            )
        }
        val personalWorkspaceRoles = memberships.associate { it.workspaceId to it.role }
        val galleryMemberIds = galleryMemberships.map { it.galleryId }.toSet()
        val galleryRows = galleries.values.map { gallery ->
            UserWorkspaceResponse(
                id = gallery.requiredId,
                kind = UserWorkspaceKind.GALLERY,
                workspaceId = gallery.workspaceId,
                galleryId = gallery.requiredId,
                name = gallery.title,
                role = if (gallery.requiredId in galleryMemberIds) {
                    WorkspaceRole.MEMBER
                } else {
                    personalWorkspaceRoles[gallery.workspaceId] ?: WorkspaceRole.OWNER
                },
                lastActivityAt = gallery.updatedAt ?: gallery.createdAt,
            )
        }
        return (studioRows + galleryRows).sortedWith(
            compareByDescending<UserWorkspaceResponse> { it.lastActivityAt }.thenByDescending { it.id },
        )
    }

    @Transactional
    fun delete(id: Long) {
        val user = userRepository.requireById(id)
        val memberships = workspaceMemberRepository.findAllByUserId(id)
        val ownedWorkspaceIds = memberships.filter { it.role == WorkspaceRole.OWNER }.map { it.workspaceId }
        val ownedWorkspaces = workspaceRepository.findAllById(ownedWorkspaceIds)
        val ownedStudioWorkspaces = ownedWorkspaces.filter { it.type == WorkspaceType.STUDIO }
        val galleryMemberships = galleryMemberRepository.findAllByUserId(id)

        ownedWorkspaces.forEach { workspace ->
            val galleryRecipients = galleryRepository.findAllByStudioId(workspace.requiredId)
                .flatMap { gallery -> galleryMemberRepository.findAllByGalleryId(gallery.requiredId) }
                .map { it.userId }
            val recipients = (workspaceMemberRepository.findAllByWorkspaceId(workspace.requiredId)
                .map { it.userId }
                + galleryRecipients)
                .filterNot { it == id }
            notificationPublisher.publish(
                userIds = recipients,
                type = UserNotificationType.WORKSPACE_DELETED,
                scope = UserNotificationScope.GLOBAL,
                scopeId = null,
                title = "작업공간이 삭제되었습니다",
                message = "${workspace.name} 작업공간이 소유자 탈퇴로 삭제되었습니다.",
            )
        }

        galleryMemberships.forEach { membership ->
            val gallery = galleryRepository.findById(membership.galleryId).orElse(null) ?: return@forEach
            val owners = workspaceMemberRepository.findAllByWorkspaceId(gallery.workspaceId)
                .filter { it.role == WorkspaceRole.OWNER }
                .map { it.userId }
            notificationPublisher.publish(
                userIds = owners,
                type = UserNotificationType.GALLERY_MEMBER_LEFT,
                scope = UserNotificationScope.GALLERY,
                scopeId = gallery.requiredId,
                title = "갤러리 멤버가 탈퇴했습니다",
                message = "${gallery.title}의 고객 한 명이 회원 탈퇴했습니다.",
            )
        }

        authTokenProvider.logout(user)
        galleryMemberRepository.deleteAll(galleryMemberships)
        workspaceMemberRepository.deleteAll(memberships)
        workspaceRepository.deleteAll(ownedStudioWorkspaces)
        userRepository.delete(user)
    }
}
