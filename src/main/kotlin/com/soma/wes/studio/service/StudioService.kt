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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class StudioService(
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
) {

    @Transactional
    fun create(userId: Long, request: CreateStudioRequest): StudioResponse {
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)
        val user = userRepository.requireById(userId)

        validateStudio(userId, normalizedGalleryUrl)

        // 현재 기본적인 회원가입 플로우는 사진작가에게만 부여, 신혼부부는 token기반 회원가입 flow를 타야한다.
        user.selectPhotographerType()

        val studio = studioRepository.save(
            Studio.create(
                userId = userId,
                name = request.name,
                galleryUrl = normalizedGalleryUrl,
                inflowChannel = request.inflowChannel,
            )
        )
        return StudioResponse.from(studio)
    }

    private fun validateStudio(userId: Long, normalizedGalleryUrl: String) {
        if (studioRepository.existsByUserId(userId)) {
            throw StudioException(StudioErrorCode.STUDIO_ALREADY_EXISTS)
        }

        if (studioRepository.existsByGalleryUrl(normalizedGalleryUrl)) {
            throw StudioException(StudioErrorCode.GALLERY_URL_DUPLICATED)
        }
    }

    @Transactional(readOnly = true)
    fun getMyStudio(userId: Long): StudioResponse{
        val studio = studioRepository.findByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)

        return StudioResponse.from(studio)
    }

    @Transactional
    fun updateMyStudio(userId: Long, request: UpdateStudioRequest): StudioResponse {
        val studio = studioRepository.findWithLockByUserId(userId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val normalizedGalleryUrl = Studio.validateGalleryUrl(request.galleryUrl)

        validateGalleryUrlChange(studio, normalizedGalleryUrl)

        studio.update(request.name, normalizedGalleryUrl)
        return StudioResponse.from(studio)
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
