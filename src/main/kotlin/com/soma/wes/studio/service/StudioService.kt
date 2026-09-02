package com.soma.wes.studio.service

import com.soma.wes.studio.domain.Studio
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
                inflowChannel = request.inflowChannel,
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
        val studios = findStudiosFor(userId)
        if (studios.isEmpty()) throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        if (studios.size > 1) throw StudioException(StudioErrorCode.STUDIO_SELECTION_REQUIRED)
        val studio = studioRepository.findWithLockByUserId(studios.single().workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)

        validateGalleryUrlChange(studio, normalizedGalleryUrl)

        studio.update(request.name, normalizedGalleryUrl)
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
        studio.update(request.name, normalizedGalleryUrl)
        workspaceRepository.findWithLockById(studio.workspaceId)?.name = request.name

        return StudioResponse.from(studio)
    }

    private fun findStudiosFor(userId: Long): List<Studio> {
        val workspaceIds = workspaceMemberRepository.findAllByUserId(userId).map { it.workspaceId }
        return studioRepository.findAllByIdInAndSuspendedAtIsNull(workspaceIds)
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
