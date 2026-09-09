package com.soma.wes.studio.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.studio.domain.Studio
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.request.ChangeStudioMemberRoleRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.dto.response.StudioMemberResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class StudioService(
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val activityRecorder: ActivityRecorder,
) {

    @Transactional
    fun create(userId: Long, request: CreateStudioRequest): StudioResponse {
        Studio.validateProfile(request.name, request.contact, request.description)
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)
        userRepository.requireById(userId)

        validateStudio(normalizedGalleryUrl)

        val workspace = workspaceRepository.save(Workspace.studio(request.name))
        workspaceMemberRepository.save(
            WorkspaceMember(
                workspaceId = workspace.requiredId,
                userId = userId,
                role = WorkspaceRole.OWNER,
            ),
        )

        val studio = studioRepository.save(
            Studio.create(
                userId = workspace.requiredId,
                name = request.name,
                galleryUrl = normalizedGalleryUrl,
                contact = request.contact,
                description = request.description,
            )
        )
        return StudioResponse.from(studio, WorkspaceRole.OWNER)
    }

    private fun validateStudio(normalizedGalleryUrl: String) {
        if (studioRepository.existsByGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }
    }

    @Transactional(readOnly = true)
    fun getMyStudio(userId: Long): StudioResponse{
        val studios = findStudiosFor(userId)
        if (studios.isEmpty()) throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (studios.size > 1) throw StudioException(StudioErrorCode.STUDIO_SELECTION_REQUIRED)

        return responseFor(studios.single(), userId)
    }

    @Transactional(readOnly = true)
    fun listMine(userId: Long): List<StudioResponse> {
        val memberships = workspaceMemberRepository.findAllByUserId(userId).associateBy { it.workspaceId }
        return studioRepository.findAllByIdInAndSuspendedAtIsNull(memberships.keys).map { studio ->
            StudioResponse.from(studio, memberships[studio.workspaceId]?.role)
        }
    }

    private fun responseFor(studio: Studio, userId: Long): StudioResponse = StudioResponse.from(
        studio,
        workspaceMemberRepository.findByWorkspaceIdAndUserId(studio.workspaceId, userId)?.role,
    )

    @Transactional(readOnly = true)
    fun get(workspaceId: Long, userId: Long): StudioResponse {
        requireStudioMember(workspaceId, userId)
        return responseFor(studioRepository.findById(workspaceId).orElseThrow(), userId)
    }

    @Transactional
    fun updateMyStudio(userId: Long, request: UpdateStudioRequest): StudioResponse {
        val studios = findOwnedStudiosFor(userId)
        if (studios.isEmpty()) throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (studios.size > 1) throw StudioException(StudioErrorCode.STUDIO_SELECTION_REQUIRED)
        val studio = studioRepository.findWithLockByUserId(studios.single().workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)

        validateGalleryUrlChange(studio, normalizedGalleryUrl)

        studio.update(request.name, normalizedGalleryUrl, request.contact, request.description)
        workspaceRepository.findWithLockById(studio.workspaceId)?.name = request.name
        return StudioResponse.from(studio, WorkspaceRole.OWNER)
    }

    @Transactional
    fun update(workspaceId: Long, userId: Long, request: UpdateStudioRequest): StudioResponse {
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                workspaceId,
                userId,
                listOf(WorkspaceRole.OWNER),
            )
        ) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
        val studio = studioRepository.findWithLockByUserId(workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)
        validateGalleryUrlChange(studio, normalizedGalleryUrl)
        studio.update(request.name, normalizedGalleryUrl, request.contact, request.description)
        workspaceRepository.findWithLockById(studio.workspaceId)?.name = request.name

        return StudioResponse.from(studio, WorkspaceRole.OWNER)
    }

    private fun findStudiosFor(userId: Long): List<Studio> {
        val workspaceIds = workspaceMemberRepository.findAllByUserId(userId).map { it.workspaceId }
        return studioRepository.findAllByIdInAndSuspendedAtIsNull(workspaceIds)
    }

    private fun findOwnedStudiosFor(userId: Long): List<Studio> {
        val workspaceIds = workspaceMemberRepository
            .findAllByUserIdAndRoleIn(userId, listOf(WorkspaceRole.OWNER))
            .map { it.workspaceId }
        return studioRepository.findAllByIdInAndSuspendedAtIsNull(workspaceIds)
    }

    @Transactional
    fun leave(workspaceId: Long, userId: Long) {
        val studio = studioRepository.findById(workspaceId).orElse(null)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val members = workspaceMemberRepository.findAllWithLockByWorkspaceId(workspaceId)
        val membership = members.find { it.userId == userId }
            ?: throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
        if (membership.role == WorkspaceRole.OWNER && members.count { it.role == WorkspaceRole.OWNER } == 1) {
            throw StudioException(StudioErrorCode.LAST_OWNER_PROTECTED)
        }

        workspaceMemberRepository.delete(membership)
        activityRecorder.recordWorkspace(workspaceId)
        val owners = workspaceMemberRepository.findAllByWorkspaceId(workspaceId)
            .filter { it.role == WorkspaceRole.OWNER }
            .map { it.userId }
        notificationPublisher.publish(
            userIds = owners,
            type = UserNotificationType.WORKSPACE_MEMBER_LEFT,
            scope = UserNotificationScope.STUDIO,
            scopeId = workspaceId,
            title = "스튜디오 멤버가 나갔습니다",
            message = "${studio.name}에서 멤버 한 명이 나갔습니다.",
        )
    }

    @Transactional(readOnly = true)
    fun listMembers(workspaceId: Long, userId: Long): List<StudioMemberResponse> {
        requireStudioMember(workspaceId, userId)
        val members = workspaceMemberRepository.findAllByWorkspaceId(workspaceId)
        val users = userRepository.findAllById(members.map { it.userId }).associateBy { it.requiredId }
        return members.mapNotNull { member -> users[member.userId]?.let { StudioMemberResponse.from(member, it) } }
            .sortedWith(compareBy<StudioMemberResponse> { it.role != WorkspaceRole.OWNER }.thenBy { it.memberId })
    }

    @Transactional
    fun changeMemberRole(
        workspaceId: Long,
        memberId: Long,
        userId: Long,
        request: ChangeStudioMemberRoleRequest,
    ): StudioMemberResponse {
        if (!studioRepository.existsByIdAndSuspendedAtIsNull(workspaceId)) {
            throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        }
        val members = workspaceMemberRepository.findAllWithLockByWorkspaceId(workspaceId)
        if (members.none { it.userId == userId && it.role == WorkspaceRole.OWNER }) {
            throw StudioException(StudioErrorCode.NOT_STUDIO_OWNER)
        }
        val target = members.find { it.requiredId == memberId }
            ?: throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
        if (target.role == WorkspaceRole.OWNER && request.role == WorkspaceRole.MEMBER &&
            members.count { it.role == WorkspaceRole.OWNER } == 1
        ) {
            throw StudioException(StudioErrorCode.LAST_OWNER_PROTECTED)
        }
        target.role = request.role
        activityRecorder.recordWorkspace(workspaceId)
        return StudioMemberResponse.from(target, userRepository.requireById(target.userId))
    }

    @Transactional
    fun removeMember(workspaceId: Long, memberId: Long, userId: Long) {
        requireStudioMember(workspaceId, userId)
        workspaceRepository.findWithLockById(workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val members = workspaceMemberRepository.findAllWithLockByWorkspaceId(workspaceId)
        if (members.none { it.userId == userId && it.role == WorkspaceRole.OWNER }) {
            throw StudioException(StudioErrorCode.NOT_STUDIO_OWNER)
        }
        val member = members.find { it.requiredId == memberId }
            ?: throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
        if (member.role == WorkspaceRole.OWNER) throw StudioException(StudioErrorCode.LAST_OWNER_PROTECTED)
        workspaceMemberRepository.delete(member)
        activityRecorder.recordWorkspace(workspaceId)
        notificationPublisher.publish(
            userIds = listOf(member.userId),
            type = UserNotificationType.MEMBERSHIP_REMOVED,
            scope = UserNotificationScope.STUDIO,
            scopeId = workspaceId,
            title = "스튜디오 소속이 해제되었습니다",
            message = "스튜디오 소유자가 멤버십을 해제했습니다.",
        )
    }

    private fun requireStudioMember(workspaceId: Long, userId: Long) {
        if (!studioRepository.existsByIdAndSuspendedAtIsNull(workspaceId) ||
            workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId) == null
        ) {
            throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
        }
    }

    @Transactional
    fun deleteMyStudio(userId: Long) {
        val studios = findOwnedStudiosFor(userId)
        if (studios.isEmpty()) throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (studios.size > 1) throw StudioException(StudioErrorCode.STUDIO_SELECTION_REQUIRED)
        deleteStudio(studios.single(), userId)
    }

    @Transactional
    fun delete(workspaceId: Long, userId: Long) {
        requireStudioMember(workspaceId, userId)
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(workspaceId, userId, listOf(WorkspaceRole.OWNER))) {
            throw StudioException(StudioErrorCode.NOT_STUDIO_OWNER)
        }
        val studio = studioRepository.findById(workspaceId).orElseThrow()
        deleteStudio(studio, userId)
    }

    private fun deleteStudio(studio: Studio, userId: Long) {
        workspaceRepository.findWithLockById(studio.workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val galleryRecipients = galleryRepository.findAllByWorkspaceId(studio.workspaceId)
            .flatMap { gallery -> galleryMemberRepository.findAllByGalleryId(gallery.requiredId) }
            .map { it.userId }
        val recipients = (workspaceMemberRepository.findAllByWorkspaceId(studio.workspaceId)
            .map { it.userId }
            + galleryRecipients)
            .filterNot { it == userId }

        notificationPublisher.publish(
            userIds = recipients,
            type = UserNotificationType.WORKSPACE_DELETED,
            scope = UserNotificationScope.GLOBAL,
            scopeId = null,
            title = "스튜디오가 삭제되었습니다",
            message = "${studio.name} 스튜디오와 소속 갤러리가 삭제되었습니다.",
        )
        workspaceRepository.deleteById(studio.workspaceId)
    }

    private fun validateGalleryUrlChange(studio: Studio, normalizedGalleryUrl: String) {
        if (normalizedGalleryUrl == studio.galleryUrl) {
            return
        }

        if (studioRepository.existsByGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }
    }

    @Transactional(readOnly = true)
    fun checkGalleryUrl(galleryUrl: String): GalleryUrlAvailabilityResponse {
        val normalizedGalleryUrl = Studio.validateGalleryUrl(galleryUrl)
        val isExists = studioRepository.existsByGalleryUrl(normalizedGalleryUrl)

        return GalleryUrlAvailabilityResponse(
            galleryUrl = normalizedGalleryUrl,
            available = !isExists,
        )
    }
}
