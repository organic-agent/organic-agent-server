package com.soma.wes.user.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.auth.dto.AccessCacheKeyDto
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.auth.service.port.AccessCache
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.port.UserNotificationPublisher
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.UserColor
import com.soma.wes.user.dto.request.ChangeUserColorRequest
import com.soma.wes.user.dto.request.UpdateUserRequest
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.domain.UserWorkspaceKind
import com.soma.wes.user.dto.response.UserWorkspaceResponse
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

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
    private val activityRepository: ActivityRepository,
    private val accessCache: AccessCache?,
) {

    @Transactional(readOnly = true)
    fun getUser(id: Long): UserResponse =
        UserResponse.from(userRepository.requireById(id)).copy(workspaces = buildWorkspaces(id))

    @Transactional
    fun update(id: Long, request: UpdateUserRequest): UserResponse {
        val user = userRepository.requireById(id)
        user.updateNickname(request.nickname.trim())
        return UserResponse.from(user).copy(workspaces = buildWorkspaces(id))
    }

    /**
     * 부부 화면의 내 색을 바꾼다. 기기마다 따로 두면 상대 화면과 내 다른 기기에서 안 보이므로 계정에 둔다.
     * null은 지우기다. 닉네임 수정(PATCH)과 경로를 나눈 것은 "안 보냄"과 "지움"을 가리지 않아도 되게 하려는 것이다.
     */
    @Transactional
    fun changeColor(id: Long, request: ChangeUserColorRequest): UserResponse {
        val color = request.color?.let { key -> UserColor.fromKey(key) ?: throw UserException(UserErrorCode.INVALID_COLOR) }

        val user = userRepository.requireById(id)
        user.changeColor(color)

        return UserResponse.from(user).copy(workspaces = buildWorkspaces(id))
    }

    @Transactional(readOnly = true)
    fun listWorkspaces(userId: Long): List<UserWorkspaceResponse> = buildWorkspaces(userId)

    private fun buildWorkspaces(userId: Long): List<UserWorkspaceResponse> {
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
        val personalGalleries = galleryRepository.findAllByWorkspaceIdIn(personalWorkspaceIds)
        val memberGalleries = galleryRepository.findAllById(galleryMemberships.map { it.galleryId })
        val galleries = (personalGalleries + memberGalleries).associateBy { it.requiredId }

        val workspaceActivity = activityRepository.findWorkspaceActivity(workspaces.keys)
        val galleryActivity = activityRepository.findGalleryActivity(galleries.keys)

        val studioRows = memberships.mapNotNull { membership ->
            val workspace = workspaces[membership.workspaceId]
                ?.takeIf { it.type == WorkspaceType.STUDIO } ?: return@mapNotNull null
            val studio = studios[workspace.requiredId] ?: return@mapNotNull null
            UserWorkspaceResponse(
                id = workspace.requiredId,
                kind = UserWorkspaceKind.STUDIO,
                workspaceId = workspace.requiredId,
                galleryId = null,
                name = studio.name,
                role = membership.role,
                lastActivityAt = listOfNotNull(studio.updatedAt, studio.createdAt, membership.updatedAt, membership.createdAt, workspaceActivity[workspace.requiredId]).maxOrNull(),
                workspaceType = WorkspaceType.STUDIO,
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
                lastActivityAt = listOfNotNull(gallery.updatedAt, gallery.createdAt, galleryActivity[gallery.requiredId]).maxOrNull(),
                workspaceType = if (gallery.workspaceId in personalWorkspaceIds) WorkspaceType.PERSONAL else WorkspaceType.STUDIO,
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
        val ownedPersonalWorkspaces = ownedWorkspaces.filter { it.type == WorkspaceType.PERSONAL }
        val galleryMemberships = galleryMemberRepository.findAllByUserId(id)

        val studioOwnerCounts = workspaceMemberRepository
            .findAllWithLockByWorkspaceIdIn(ownedWorkspaces.filter { it.type == WorkspaceType.STUDIO }.map { it.requiredId })
            .filter { it.role == WorkspaceRole.OWNER }
            .groupingBy { it.workspaceId }
            .eachCount()
        val ownedStudioWorkspaces = ownedWorkspaces.filter { studioOwnerCounts[it.requiredId] == 1 }
        val deletedWorkspaces = ownedPersonalWorkspaces + ownedStudioWorkspaces
        val deletedWorkspaceIds = deletedWorkspaces.map { it.requiredId }.toSet()

        val leftWorkspaceIds = memberships.map { it.workspaceId }.filter { it !in deletedWorkspaceIds }
        val galleries = galleryRepository.findAllById(galleryMemberships.map { it.galleryId })
        val galleryById = galleries.associateBy { it.requiredId }
        val relatedWorkspaceIds = leftWorkspaceIds + deletedWorkspaceIds + galleries.map { it.workspaceId }
        val membersByWorkspaceId = workspaceMemberRepository.findAllByWorkspaceIdIn(relatedWorkspaceIds.toSet())
            .groupBy { it.workspaceId }
        val workspaceById = workspaceRepository.findAllById(leftWorkspaceIds).associateBy { it.requiredId }

        for (workspaceId in leftWorkspaceIds) {
            val workspace = workspaceById[workspaceId] ?: continue
            notificationPublisher.publish(
                userIds = membersByWorkspaceId[workspaceId].orEmpty()
                    .filter { it.role == WorkspaceRole.OWNER && it.userId != id }.map { it.userId },
                type = UserNotificationType.WORKSPACE_MEMBER_LEFT,
                scope = if (workspace.type == WorkspaceType.STUDIO) UserNotificationScope.STUDIO else UserNotificationScope.GLOBAL,
                scopeId = if (workspace.type == WorkspaceType.STUDIO) workspace.requiredId else null,
                title = "멤버가 탈퇴했습니다",
                message = "${workspace.name}의 멤버 한 명이 회원 탈퇴했습니다.",
            )
        }

        val deletedGalleries = galleryRepository.findAllByWorkspaceIdIn(deletedWorkspaceIds)
        val workspaceIdByGalleryId = deletedGalleries.associate { it.requiredId to it.workspaceId }
        val galleryMemberIdsByWorkspaceId = galleryMemberRepository.findAllByGalleryIdIn(workspaceIdByGalleryId.keys)
            .groupBy(keySelector = { workspaceIdByGalleryId.getValue(it.galleryId) }, valueTransform = { it.userId })
        for (workspace in deletedWorkspaces) {
            val recipients = (membersByWorkspaceId[workspace.requiredId].orEmpty().map { it.userId }
                + galleryMemberIdsByWorkspaceId[workspace.requiredId].orEmpty())
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

        for (membership in galleryMemberships) {
            val gallery = galleryById[membership.galleryId] ?: continue
            val owners = membersByWorkspaceId[gallery.workspaceId].orEmpty()
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
        workspaceRepository.deleteAll(deletedWorkspaces)
        userRepository.delete(user)
        evictAccessAfterCommit(id, deletedWorkspaceIds)
    }

    /**
     * 탈퇴한 계정과 함께 지운 작업공간을 권한 확인 캐시에서 지운다(#286 (k)) — 남은 access token(최대 30분)이 캐시의 "활성"으로 통과하지 않게.
     * 커밋 뒤에 지운다: 커밋 전에 지우면 그 사이의 요청이 아직 활성인 행을 다시 담는다.
     */
    private fun evictAccessAfterCommit(userId: Long, workspaceIds: Collection<Long>) {
        val cache = accessCache ?: return
        val keys = listOf(AccessCacheKeyDto.userStatus(userId)) + workspaceIds.map(AccessCacheKeyDto::workspace)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = cache.evict(keys)
            },
        )
    }
}
