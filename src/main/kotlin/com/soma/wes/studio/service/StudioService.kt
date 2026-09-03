package com.soma.wes.studio.service

import com.soma.wes.studio.domain.Studio
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
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
) {

    @Transactional
    fun create(userId: Long, request: CreateStudioRequest): StudioResponse {
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
        return StudioResponse.from(studio)
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

        return StudioResponse.from(studios.single())
    }

    @Transactional(readOnly = true)
    fun listMine(userId: Long): List<StudioResponse> = findStudiosFor(userId).map(StudioResponse::from)

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
        return StudioResponse.from(studio)
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

        return StudioResponse.from(studio)
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
        val membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
        if (membership.role == WorkspaceRole.OWNER) {
            throw StudioException(StudioErrorCode.STUDIO_OWNER_CANNOT_LEAVE)
        }

        workspaceMemberRepository.delete(membership)
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

    @Transactional
    fun deleteMyStudio(userId: Long) {
        val studios = findOwnedStudiosFor(userId)
        if (studios.isEmpty()) throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (studios.size > 1) throw StudioException(StudioErrorCode.STUDIO_SELECTION_REQUIRED)
        val studio = studios.single()
        val galleryRecipients = galleryRepository.findAllByStudioId(studio.workspaceId)
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
